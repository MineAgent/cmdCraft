/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Copyright (C) 2026 MineAgent
 */

package com.craftcmd;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.util.Locale;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Builds the {@code /cmdop look <yaw|pitch> <angle>} subcommand: turns where the local player is looking.
 *
 * <p>The command is purely client side and only writes the player's rotation fields, which is exactly
 * what the mouse look does — the next client tick then syncs the new rotation to the server with a
 * regular {@code ServerboundMovePlayerPacket.Rot}. As a consequence the change is what
 * AdvancedInfoFetcher's {@code /info} reports for {@code yaw} and {@code pitch}.
 *
 * <p>The angle uses vanilla's {@code ~} notation: {@code look yaw 90} sets the yaw to 90°,
 * {@code look yaw ~0.1} adds 0.1° and {@code look yaw ~-20} subtracts 20°. Pitch is clamped to
 * vanilla's {@code -90..90}, yaw is stored as given (it may exceed ±180).
 */
public final class LookCommand {
	private static final float MIN_PITCH = -90.0F;
	private static final float MAX_PITCH = 90.0F;

	private LookCommand() {
	}

	/** @return the {@code look} node, to be attached below {@code /cmdop} */
	public static LiteralArgumentBuilder<FabricClientCommandSource> command() {
		return ClientCommands.literal("look")
				.then(ClientCommands.literal("yaw")
						.then(ClientCommands.argument("angle", AngleArgumentType.INSTANCE)
								.executes(context -> look(context, true))))
				.then(ClientCommands.literal("pitch")
						.then(ClientCommands.argument("angle", AngleArgumentType.INSTANCE)
								.executes(context -> look(context, false))));
	}

	/**
	 * Applies one rotation instruction.
	 *
	 * @param yaw {@code true} to turn the yaw, {@code false} to tilt the pitch
	 * @return {@code 1} when the view was turned, {@code 0} when there is no player
	 */
	private static int look(CommandContext<FabricClientCommandSource> context, boolean yaw) {
		FabricClientCommandSource source = context.getSource();
		Minecraft client = source.getClient();

		if (client.player == null || client.level == null) {
			source.sendError(Component.translatable("craftcmd.error.not_ready"));
			return 0;
		}

		LocalPlayer player = client.player;
		Angle angle = context.getArgument("angle", Angle.class);

		float before = yaw ? player.getYRot() : player.getXRot();
		double target = angle.relative() ? before + angle.value() : angle.value();
		float after = yaw ? setYaw(player, target) : setPitch(player, target);

		source.sendFeedback(Component.translatable("craftcmd.look.done",
				Component.literal(yaw ? "yaw" : "pitch"), decimal(after), decimal(before)));
		CraftCmdMod.LOGGER.info("[craftcmd] look {} -> {} (was {}, {})", yaw ? "yaw" : "pitch", after, before,
				angle.relative() ? "relative " + angle.value() : "absolute");
		return 1;
	}

	/**
	 * Writes the yaw (stored as given, exactly like the mouse look does) and snaps the interpolated
	 * previous rotation so the camera turns immediately instead of easing over one tick.
	 */
	private static float setYaw(LocalPlayer player, double value) {
		float yaw = (float) value;
		player.setYRot(yaw);
		player.yRotO = yaw;
		return yaw;
	}

	/** Writes the pitch, clamped to {@code -90..90} (straight up .. straight down) like the mouse look. */
	private static float setPitch(LocalPlayer player, double value) {
		float pitch = Mth.clamp((float) value, MIN_PITCH, MAX_PITCH);
		player.setXRot(pitch);
		player.xRotO = pitch;
		return pitch;
	}

	/** One decimal, matching what AdvancedInfoFetcher's {@code /info} prints. */
	private static String decimal(float value) {
		return String.format(Locale.ROOT, "%.1f", value);
	}
}
