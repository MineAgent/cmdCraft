/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Copyright (C) 2026 MineAgent
 */

package com.craftcmd;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Drives the {@code mine} command: destroys the block under the crosshair with the item that is
 * currently in the main hand.
 *
 * <p>The target is captured when the command runs (the same {@code Minecraft#hitResult} that
 * AdvancedInfoFetcher reports as the targeted block), and from then on this job behaves like a
 * player holding the left mouse button: one {@code continueDestroyBlock} per tick, plus the swing
 * animation and punch packet the vanilla client sends while mining. {@code MinecraftMixin} cancels
 * {@code Minecraft#continueAttack} while the job runs, because the vanilla path would send
 * {@code ABORT_DESTROY_BLOCK} every tick and reset the mining progress.</p>
 *
 * <p>No tool is selected or swapped: the held item decides the breaking speed (and, as in vanilla,
 * whether the block drops anything at all). Unbreakable blocks are rejected by the command before a
 * job is started.</p>
 */
public final class MineJob {
	/** Ticks to wait after the block turned into air, before the break is reported as done. */
	private static final int CONFIRM_TICKS = 10;
	private static final int MIN_DEADLINE = 100;
	private static final int MAX_DEADLINE = 2100;

	private static MineJob active;

	private final Minecraft client;
	private final OpCommandSource source;
	private final BlockPos pos;
	private final Direction face;
	private final Block initialBlock;
	private final Identifier blockId;
	private int deadline;
	private int confirmTicks = -1;

	private MineJob(Minecraft client, BlockPos pos, Direction face, BlockState initial, OpCommandSource source) {
		this.client = client;
		this.source = source;
		this.pos = pos;
		this.face = face;
		this.initialBlock = initial.getBlock();
		this.blockId = BuiltInRegistries.BLOCK.getKey(this.initialBlock);
		this.deadline = deadlineFor(initial.getDestroyProgress(client.player, client.level, pos));
	}

	public static boolean isRunning() {
		return active != null;
	}

	/** Starts mining {@code pos}; the command already did the reachability and permission checks. */
	public static void start(Minecraft client, BlockPos pos, Direction face, BlockState initial,
			OpCommandSource source) {
		active = new MineJob(client, pos, face, initial, source);
		CraftCmdMod.LOGGER.info("[craftcmd] mine {} at {} {} {}", BuiltInRegistries.BLOCK.getKey(initial.getBlock()),
				pos.getX(), pos.getY(), pos.getZ());
	}

	public static void tick(Minecraft client) {
		MineJob job = active;

		if (job == null) {
			return;
		}

		try {
			job.step();
		} catch (Throwable throwable) {
			CraftCmdMod.LOGGER.error("[craftcmd] unexpected error while mining", throwable);
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
			this.abort(Component.translatable("craftcmd.mine.error.timeout", this.initialBlock.getName()));
			return;
		}

		BlockState state = this.client.level.getBlockState(this.pos);

		if (state.isAir()) {
			if (this.confirmTicks < 0) {
				this.confirmTicks = CONFIRM_TICKS;
			}

			if (--this.confirmTicks <= 0) {
				this.finish();
			}

			return;
		}

		if (this.confirmTicks >= 0) {
			// The client predicted the block away, but the server put it back (protection, lag rollback).
			this.abort(Component.translatable("craftcmd.mine.error.changed"));
			return;
		}

		if (state.getBlock() != this.initialBlock) {
			this.abort(Component.translatable("craftcmd.mine.error.changed"));
			return;
		}

		if (!player.isWithinBlockInteractionRange(this.pos, 1.0D)) {
			this.abort(Component.translatable("craftcmd.mine.error.out_of_range"));
			return;
		}

		this.client.gameMode.continueDestroyBlock(this.pos, this.face);

		ItemStack hand = player.getMainHandItem();
		player.swing(InteractionHand.MAIN_HAND, hand.getAttackAnimation(), false);
		player.connection.send(ServerboundPunchPacket.INSTANCE);
	}

	/** Expected breaking time for the held item, with slack, bounded so the HTTP request answers in time. */
	private static int deadlineFor(float progress) {
		if (progress <= 0.0F) {
			return MAX_DEADLINE;
		}

		long expected = (long) Math.ceil(1.0D / progress);
		long ticks = expected + 100L;
		return (int) Math.max(MIN_DEADLINE, Math.min(MAX_DEADLINE, ticks));
	}

	private void finish() {
		active = null;

		if (this.client.gameMode != null && this.client.gameMode.isDestroying()) {
			this.client.gameMode.stopDestroyBlock();
		}

		this.source.sendFeedback(Component.translatable("craftcmd.mine.done", this.initialBlock.getName(),
				this.pos.getX(), this.pos.getY(), this.pos.getZ()));
		CraftCmdMod.LOGGER.info("[craftcmd] broke {} at {} {} {}", this.blockId, this.pos.getX(), this.pos.getY(),
				this.pos.getZ());
		this.source.complete();
	}

	private void abort(Component message) {
		active = null;

		if (this.client.gameMode != null) {
			this.client.gameMode.stopDestroyBlock();
		}

		this.source.sendError(message);
		CraftCmdMod.LOGGER.warn("[craftcmd] mining aborted: {}", message.getString());
		this.source.complete();
	}
}
