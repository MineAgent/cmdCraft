/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Copyright (C) 2026 MineAgent
 */

package com.craftcmd;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.GameMasterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Builds the {@code mine} command: destroys the block under the crosshair with the item currently in
 * the main hand.
 *
 * <p>The target is read from {@code Minecraft#hitResult}, the same pick result AdvancedInfoFetcher
 * reports as the targeted block. A {@link BlockHitResult} of type {@link HitResult.Type#MISS} is not
 * a real target, so the type is checked as well. Everything about the block and the current mode is
 * validated before the first destroy packet is sent; an unbreakable block (hardness -1, e.g. bedrock
 * or a barrier) is rejected instead of being mined forever.</p>
 *
 * <p>Mining takes several ticks, so the command starts a {@link MineJob} and lets it complete the
 * {@link OpCommandSource} when the block is gone or the job gives up.</p>
 */
public final class MineCommand {
	private MineCommand() {
	}

	/** @return the {@code mine} node, registered at the dispatcher root */
	public static LiteralArgumentBuilder<OpCommandSource> command() {
		return LiteralArgumentBuilder.<OpCommandSource>literal("mine").executes(MineCommand::mine);
	}

	private static int mine(CommandContext<OpCommandSource> context) {
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

		HitResult hit = client.hitResult;

		if (!(hit instanceof BlockHitResult blockHit) || blockHit.getType() != HitResult.Type.BLOCK) {
			source.sendError(Component.translatable("craftcmd.mine.error.no_target"));
			return 0;
		}

		BlockPos pos = blockHit.getBlockPos();
		BlockState state = client.level.getBlockState(pos);

		if (state.isAir()) {
			source.sendError(Component.translatable("craftcmd.mine.error.no_target"));
			return 0;
		}

		if (state.getDestroySpeed(client.level, pos) < 0.0F
				|| state.getDestroyProgress(player, client.level, pos) <= 0.0F) {
			source.sendError(Component.translatable("craftcmd.mine.error.unbreakable", state.getBlock().getName()));
			return 0;
		}

		if (isRestricted(client, player, pos, state)) {
			source.sendError(Component.translatable("craftcmd.mine.error.restricted", state.getBlock().getName()));
			return 0;
		}

		Direction face = blockHit.getDirection();

		if (!client.gameMode.startDestroyBlock(pos, face)) {
			source.sendError(Component.translatable("craftcmd.mine.error.restricted", state.getBlock().getName()));
			return 0;
		}

		MineJob.start(client, pos, face, state, source);
		// The dispatcher must not complete this request: the job does, when it finishes or aborts.
		source.expectAsync();
		return 1;
	}

	/** Vanilla's own checks for "may this player break this block here". */
	private static boolean isRestricted(Minecraft client, LocalPlayer player, BlockPos pos, BlockState state) {
		GameType mode = client.gameMode.getPlayerMode();

		return player.blockActionRestricted(client.level, pos, mode)
				|| !client.level.getWorldBorder().isWithinBounds(pos)
				|| (state.getBlock() instanceof GameMasterBlock && !player.canUseGameMasterBlocks());
	}
}
