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
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Consumable;

/**
 * Builds the {@code eat [amount]} command: consumes the stack in the main hand.
 *
 * <p>Anything with a vanilla {@link Consumable} component counts, so this covers food, potions, milk
 * buckets, honey bottles, spider eyes and the like. Only the main hand stack is inspected and used —
 * the rest of the inventory is never searched or touched, which is why a count larger than the held
 * stack is rejected up front.</p>
 *
 * <p>The actual eating takes several ticks per item, so the command starts an {@link EatJob} and lets
 * it complete the {@link OpCommandSource} when it is done.</p>
 */
public final class EatCommand {
	/**
	 * Upper bound for the argument. The real limit is the held stack (checked in the command, so a
	 * too large count produces the "only this slot" error instead of a parse error); this only keeps
	 * the parsed number sane.
	 */
	public static final int MAX_AMOUNT = 2304;

	private EatCommand() {
	}

	/** @return the {@code eat} node, registered at the dispatcher root */
	public static LiteralArgumentBuilder<OpCommandSource> command() {
		return LiteralArgumentBuilder.<OpCommandSource>literal("eat")
				.executes(context -> eat(context, 1))
				.then(RequiredArgumentBuilder
						.<OpCommandSource, Integer>argument("amount", IntegerArgumentType.integer(1, MAX_AMOUNT))
						.executes(context -> eat(context, IntegerArgumentType.getInteger(context, "amount"))));
	}

	private static int eat(CommandContext<OpCommandSource> context, int amount) {
		OpCommandSource source = context.getSource();
		Minecraft client = Minecraft.getInstance();

		if (Jobs.isBusy()) {
			source.sendError(Component.translatable("craftcmd.error.busy_job"));
			return 0;
		}

		if (client.player == null || client.level == null || client.gameMode == null) {
			source.sendError(Component.translatable("craftcmd.error.not_ready"));
			return 0;
		}

		LocalPlayer player = client.player;

		if (player.isSpectator()) {
			source.sendError(Component.translatable("craftcmd.error.spectator"));
			return 0;
		}

		if (player.isUsingItem()) {
			source.sendError(Component.translatable("craftcmd.eat.error.using"));
			return 0;
		}

		ItemStack held = player.getMainHandItem();

		if (held.isEmpty()) {
			source.sendError(Component.translatable("craftcmd.eat.error.empty_hand"));
			return 0;
		}

		Consumable consumable = held.get(DataComponents.CONSUMABLE);

		if (consumable == null) {
			source.sendError(Component.translatable("craftcmd.eat.error.not_consumable", held.getHoverName()));
			return 0;
		}

		if (amount > held.getCount()) {
			source.sendError(Component.translatable("craftcmd.eat.error.not_enough", held.getHoverName(),
					held.getCount(), amount));
			return 0;
		}

		if (!consumable.canConsume(player, held)) {
			source.sendError(Component.translatable("craftcmd.eat.error.full", held.getHoverName()));
			return 0;
		}

		EatJob.start(client, held, amount, source);
		// The dispatcher must not complete this request: the job does, when it finishes or aborts.
		source.expectAsync();
		return 1;
	}
}
