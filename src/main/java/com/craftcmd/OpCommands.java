/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Copyright (C) 2026 MineAgent
 */

package com.craftcmd;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * The command dispatcher behind {@code POST /op/}.
 *
 * <p>The five operations of the mod ({@code craft}, {@code inventory}, {@code furnace},
 * {@code chest}, {@code look}) are plain Brigadier command trees whose source is an
 * {@link OpCommandSource} instead of a chat command source. That keeps the exact same parsing rules
 * (item ids, {@code ~} angles, furnace slots, amount ranges) and the same validation messages, but
 * the outcome is written into the HTTP response instead of the chat HUD.</p>
 *
 * <p>{@link #dispatch} runs on the client (render) thread; it is handed to
 * {@link Minecraft#execute} by the endpoint, because container clicks and player rotation may only
 * be touched there.</p>
 */
public final class OpCommands {
	private static final CommandDispatcher<OpCommandSource> DISPATCHER = new CommandDispatcher<>();

	static {
		DISPATCHER.register(CraftCommand.command());
		DISPATCHER.register(InventoryCommand.command());
		DISPATCHER.register(FurnaceCommand.command());
		DISPATCHER.register(ChestCommand.command());
		DISPATCHER.register(LookCommand.command());
	}

	private OpCommands() {
	}

	/**
	 * Parses one command line and runs it against the game. Runs on the client thread and always
	 * completes {@code source} unless the command started an asynchronous job.
	 *
	 * @param line   the command, without the {@code /cmdop} prefix (e.g. {@code craft stick 4})
	 * @param source collects the feedback and failure of this request
	 * @param client the running client, used for the readiness check
	 */
	public static void dispatch(String line, OpCommandSource source, Minecraft client) {
		try {
			if (client.player == null || client.level == null || client.gameMode == null) {
				source.sendNotReady(Component.translatable("craftcmd.error.not_ready"));
				return;
			}

			DISPATCHER.execute(line, source);
		} catch (CommandSyntaxException e) {
			source.sendError(Component.literal(syntaxError(e, line)));
		} catch (Throwable throwable) {
			CraftCmdMod.LOGGER.error("[craftcmd] command failed: {}", line, throwable);
			source.sendError(Component.translatable("craftcmd.error.internal"));
		} finally {
			if (!source.isAsync()) {
				source.complete();
			}
		}
	}

	/**
	 * Turns Brigadier's parse failure into a readable, multi-line message: the reason, the offending
	 * line and a caret below the cursor, plus a pointer at the manual.
	 */
	private static String syntaxError(CommandSyntaxException e, String line) {
		String input = e.getInput() == null ? line : e.getInput();
		String reason = Component.translatable("craftcmd.error.bad_request", e.getRawMessage().getString()).getString();
		StringBuilder out = new StringBuilder(reason);

		if (!input.isEmpty()) {
			int cursor = Math.max(0, Math.min(e.getCursor(), input.length()));
			out.append('\n').append(input).append('\n');

			for (int i = 0; i < cursor; i++) {
				out.append(' ');
			}

			out.append('^');
		}

		out.append('\n').append(Component.translatable("craftcmd.error.see_help").getString());
		return out.toString();
	}
}
