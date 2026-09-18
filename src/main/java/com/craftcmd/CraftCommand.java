/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Copyright (C) 2026 MineAgent
 */

package com.craftcmd;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.Slot;

/** Registers {@code /craft <item id> [amount]}. */
public final class CraftCommand {
	/** Upper bound for the amount argument; 36 slots of 64 items. */
	public static final int MAX_AMOUNT = 2304;

	private CraftCommand() {
	}

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(
				ClientCommands.literal("craft")
						.then(ClientCommands.argument("item", ItemIdArgumentType.INSTANCE)
								.executes(context -> execute(context, 1))
								.then(ClientCommands.argument("amount", IntegerArgumentType.integer(1, MAX_AMOUNT))
										.executes(context -> execute(context,
												IntegerArgumentType.getInteger(context, "amount"))))));
	}

	private static int execute(CommandContext<FabricClientCommandSource> context, int amount) {
		FabricClientCommandSource source = context.getSource();
		Minecraft client = source.getClient();

		if (CraftJob.isRunning()) {
			source.sendError(Component.translatable("craftcmd.error.busy"));
			return 0;
		}

		if (client.player == null || client.level == null || client.gameMode == null) {
			source.sendError(Component.translatable("craftcmd.error.not_ready"));
			return 0;
		}

		if (!(client.player.containerMenu instanceof AbstractCraftingMenu menu)) {
			source.sendError(Component.translatable("craftcmd.error.no_menu"));
			return 0;
		}

		if (!menu.getCarried().isEmpty()) {
			source.sendError(Component.translatable("craftcmd.error.cursor"));
			return 0;
		}

		for (Slot slot : menu.getInputGridSlots()) {
			if (!slot.getItem().isEmpty()) {
				source.sendError(Component.translatable("craftcmd.error.grid_not_empty"));
				return 0;
			}
		}

		Identifier itemId = context.getArgument("item", Identifier.class);
		CraftPlan plan;

		try {
			plan = CraftPlan.build(client, itemId, amount, menu);
		} catch (CraftPlan.Failure failure) {
			source.sendError(failure.message);
			CraftCmdMod.LOGGER.info("[craftcmd] rejected {}: {}", itemId, failure.message.getString());
			return 0;
		}

		CraftJob.start(client, plan, source);
		source.sendFeedback(Component.translatable("craftcmd.msg.started", plan.result().getHoverName(),
				plan.totalResultCount()));
		return 1;
	}
}
