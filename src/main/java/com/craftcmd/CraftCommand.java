/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Copyright (C) 2026 MineAgent
 */

package com.craftcmd;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.Slot;

/**
 * Builds the {@code craft <item id> [amount]} command: crafts from the materials the player carries
 * through the vanilla crafting grid.
 *
 * <p>The request only returns once the job is over: {@link CraftJob} keeps clicking on the following
 * client ticks and completes the {@link OpCommandSource} when it is done, which is what lets
 * {@code POST /op/} answer with the real outcome instead of "started".</p>
 */
public final class CraftCommand {
	/** Upper bound for the amount argument; 36 slots of 64 items. */
	public static final int MAX_AMOUNT = 2304;

	private CraftCommand() {
	}

	/** @return the {@code craft} node, registered at the dispatcher root */
	public static LiteralArgumentBuilder<OpCommandSource> command() {
		return LiteralArgumentBuilder.<OpCommandSource>literal("craft")
				.then(RequiredArgumentBuilder
						.<OpCommandSource, Identifier>argument("item", ItemIdArgumentType.INSTANCE)
						.executes(context -> execute(context, 1))
						.then(RequiredArgumentBuilder
								.<OpCommandSource, Integer>argument("amount", IntegerArgumentType.integer(1, MAX_AMOUNT))
								.executes(context -> execute(context,
										IntegerArgumentType.getInteger(context, "amount")))));
	}

	private static int execute(CommandContext<OpCommandSource> context, int amount) {
		OpCommandSource source = context.getSource();
		Minecraft client = Minecraft.getInstance();

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
		// The dispatcher must not complete this request: the job does, when it finishes or aborts.
		source.expectAsync();
		return 1;
	}
}
