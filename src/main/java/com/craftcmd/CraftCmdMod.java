/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Copyright (C) 2026 MineAgent
 */

package com.craftcmd;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point of the (client only) mod.
 *
 * <p>Every command lives below {@code /cmdop}: {@code /cmdop craft} looks the recipe up in the client side
 * recipe book, {@code /cmdop furnace} and {@code /cmdop chest} drive the container screen that is open,
 * {@code /cmdop inventory} swaps hotbar slots and {@code /cmdop look} turns the view. All of it goes
 * through ordinary player actions (container clicks, movement packets), so no server side component and no
 * mixin is required and the mod also works on unmodified servers.
 */
public final class CraftCmdMod implements ClientModInitializer {
	public static final String MOD_ID = "craftcmd";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitializeClient() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> dispatcher.register(
				ClientCommands.literal("cmdop")
						.then(CraftCommand.command())
						.then(InventoryCommand.command())
						.then(FurnaceCommand.command())
						.then(ChestCommand.command())
						.then(LookCommand.command())));
		ClientTickEvents.END_CLIENT_TICK.register(CraftJob::tick);
	}
}
