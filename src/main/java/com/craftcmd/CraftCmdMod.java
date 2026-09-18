/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Copyright (C) 2026 MineAgent
 */

package com.craftcmd;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point of the (client only) mod.
 *
 * <p>All the work happens on the client: the {@code /craft} command looks the recipe up in the
 * client side recipe book, checks the player's inventory, and then drives the vanilla crafting
 * grid through regular {@code ServerboundContainerClickPacket}s. No server side component and no
 * mixin is required, which means the mod also works on unmodified servers.
 */
public final class CraftCmdMod implements ClientModInitializer {
	public static final String MOD_ID = "craftcmd";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitializeClient() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> CraftCommand.register(dispatcher));
		ClientTickEvents.END_CLIENT_TICK.register(CraftJob::tick);
	}
}
