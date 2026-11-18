/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Copyright (C) 2026 MineAgent
 */

package com.craftcmd;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.component.Consumable;

/**
 * Drives the {@code eat [amount]} command: consumes the item in the main hand a requested number of
 * times, one item per vanilla use.
 *
 * <p>Eating and drinking are server side operations: {@code MultiPlayerGameMode#useItem} only sends
 * {@code ServerboundUseItemPacket} and predicts locally, the server completes the use after the
 * item's consume time and syncs the new stack back. The client itself cancels any use whose mouse
 * button is not held down in {@code Minecraft#handleKeybinds}, so {@code MultiPlayerGameModeMixin}
 * suppresses {@code releaseUsingItem} for as long as this job runs.</p>
 *
 * <p>An item counts as consumed as soon as the main hand stack lost one item (food, spider eye, ...)
 * or changed into another stack (potion → glass bottle, milk bucket → bucket). Only the main hand is
 * ever touched, and only the requested amount is consumed.</p>
 */
public final class EatJob {
	/** Vanilla's default consume time, used when an item has no {@link Consumable} (should not happen). */
	private static final int DEFAULT_CONSUME_TICKS = 32;
	/** Extra ticks granted for the server to answer and sync the stack back. */
	private static final int SLACK_TICKS = 100;
	/** How long to wait for the server to clear the "using item" flag before forcing a release. */
	private static final int STUCK_TICKS = 60;
	/** Overall cap; keeps the HTTP request below the endpoint's job timeout. */
	private static final int DEADLINE_TICKS = 2400;

	private static EatJob active;

	private final Minecraft client;
	private final OpCommandSource source;
	private final ItemStack template;
	private final Consumable consumable;
	private final ItemUseAnimation animation;
	private final int requested;
	private int eaten;
	private int deadline;
	private int waitTicks;
	private int elapsedTicks;
	private int stuckTicks;
	private boolean prepared;
	private boolean forceRelease;
	private int snapshotCount;
	private ItemStack snapshot = ItemStack.EMPTY;

	private EatJob(Minecraft client, ItemStack stack, int requested, OpCommandSource source) {
		this.client = client;
		this.source = source;
		this.template = stack.copyWithCount(1);
		this.consumable = stack.get(DataComponents.CONSUMABLE);
		this.animation = this.consumable == null ? ItemUseAnimation.EAT : this.consumable.animation();
		this.requested = requested;
		this.deadline = DEADLINE_TICKS;
		this.stuckTicks = STUCK_TICKS;
	}

	public static boolean isRunning() {
		return active != null;
	}

	/**
	 * @return true while the vanilla "key released" path must be blocked, i.e. an eat job owns the
	 *         item use in progress
	 */
	public static boolean suppressesRelease() {
		EatJob job = active;
		return job != null && !job.forceRelease;
	}

	/** Starts consuming {@code amount} items from the given stack; the command already validated it. */
	public static void start(Minecraft client, ItemStack stack, int amount, OpCommandSource source) {
		active = new EatJob(client, stack, amount, source);
		CraftCmdMod.LOGGER.info("[craftcmd] eat {} x{} from the main hand", stack.getItem(), amount);
	}

	public static void tick(Minecraft client) {
		EatJob job = active;

		if (job == null) {
			return;
		}

		try {
			job.step();
		} catch (Throwable throwable) {
			CraftCmdMod.LOGGER.error("[craftcmd] unexpected error while eating", throwable);
			job.abort(Component.translatable("craftcmd.error.internal"));
		}
	}

	private void step() {
		LocalPlayer player = this.client.player;

		if (player == null || this.client.level == null || this.client.gameMode == null) {
			this.abort(Component.translatable("craftcmd.error.not_ready"));
			return;
		}

		if (--this.deadline <= 0) {
			this.abort(Component.translatable("craftcmd.eat.error.timeout", this.eaten, this.requested));
			return;
		}

		ItemStack held = player.getMainHandItem();

		if (this.prepared) {
			this.elapsedTicks++;

			if (this.consumed(held) || this.finishedWithInfiniteMaterials(player)) {
				this.eaten++;
				this.prepared = false;

				if (this.eaten >= this.requested) {
					this.finish();
				}

				return;
			}

			if (--this.waitTicks <= 0) {
				this.abort(Component.translatable("craftcmd.eat.error.timeout", this.eaten, this.requested));
			}

			return;
		}

		if (!ItemStack.isSameItemSameComponents(held, this.template)) {
			this.abort(Component.translatable("craftcmd.eat.error.changed", this.eaten, this.requested));
			return;
		}

		if (player.isUsingItem()) {
			// The previous item was consumed but the server has not cleared the flag yet. Normally
			// that happens within a tick or two; force a release only when the sync seems lost.
			if (--this.stuckTicks <= 0) {
				this.forceRelease = true;

				try {
					this.client.gameMode.releaseUsingItem(player);
				} finally {
					this.forceRelease = false;
					this.stuckTicks = STUCK_TICKS;
				}
			}

			return;
		}

		this.stuckTicks = STUCK_TICKS;

		if (!this.consumable.canConsume(player, held)) {
			this.abort(Component.translatable("craftcmd.eat.error.full", this.template.getHoverName()));
			return;
		}

		InteractionResult result = this.client.gameMode.useItem(player, InteractionHand.MAIN_HAND);

		if (result == null || !result.consumesAction()) {
			this.abort(Component.translatable("craftcmd.eat.error.failed"));
			return;
		}

		this.prepared = true;
		this.elapsedTicks = 0;
		this.snapshot = held.copy();
		this.snapshotCount = held.getCount();
		this.waitTicks = Math.max(this.consumable == null ? DEFAULT_CONSUME_TICKS : this.consumable.consumeTicks(),
				20) + SLACK_TICKS;
	}

	/** One item counts as consumed when the main hand stack lost one or turned into another stack. */
	private boolean consumed(ItemStack held) {
		return held.isEmpty()
				|| held.getCount() < this.snapshotCount
				|| !ItemStack.isSameItemSameComponents(held, this.snapshot);
	}

	/**
	 * Creative players have infinite materials, so vanilla does not shrink the stack (and does not
	 * convert it into a remainder) when the use completes. There the end of the use itself is the
	 * consumption: the server clears the "using item" flag once it has run the whole consume time.
	 */
	private boolean finishedWithInfiniteMaterials(LocalPlayer player) {
		return player.hasInfiniteMaterials()
				&& !player.isUsingItem()
				&& this.elapsedTicks >= Math.max(this.consumable == null ? DEFAULT_CONSUME_TICKS
						: this.consumable.consumeTicks() - 2, 1);
	}

	private void finish() {
		String key = this.animation == ItemUseAnimation.DRINK ? "craftcmd.drink.done" : "craftcmd.eat.done";
		this.source.sendFeedback(Component.translatable(key, this.template.getHoverName(), this.eaten));
		CraftCmdMod.LOGGER.info("[craftcmd] consumed {} x{}", this.template.getItem(), this.eaten);
		active = null;
		this.source.complete();
	}

	private void abort(Component message) {
		active = null;
		stopUsingItem();
		this.source.sendError(message);
		CraftCmdMod.LOGGER.warn("[craftcmd] eating aborted: {}", message.getString());
		this.source.complete();
	}

	/** Stops a use that is still in flight, so the server is not left using the item. */
	private void stopUsingItem() {
		LocalPlayer player = this.client.player;

		if (player == null || this.client.gameMode == null || !player.isUsingItem()) {
			return;
		}

		this.forceRelease = true;

		try {
			this.client.gameMode.releaseUsingItem(player);
		} finally {
			this.forceRelease = false;
		}
	}
}
