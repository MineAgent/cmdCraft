/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Copyright (C) 2026 MineAgent
 */

package com.craftcmd;

import com.example.httpd.HttpdProvider;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entrypoint: mounts the mod's endpoints under {@code /op} on MGHttpdProvider's shared server
 * (127.0.0.1:3420).
 *
 * <p>Five operations are served from there: {@code craft} looks the recipe up in the client side
 * recipe book, {@code furnace} and {@code chest} drive the container screen that is open,
 * {@code inventory} swaps hotbar slots and {@code look} turns the view. All of it goes through
 * ordinary player actions (container clicks, movement packets), so no server side component is
 * required and the mod also works on unmodified servers.</p>
 *
 * <p>The provider owns the HTTP server and the client-exit handling, so this entrypoint only
 * registers the {@code /op} prefix.</p>
 */
public final class CraftCmdMod implements ClientModInitializer {
	public static final String MOD_ID = "craftcmd";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitializeClient() {
		HttpdProvider.register(OpEndpoint.PREFIX, OpEndpoint.NAME, OpEndpoint.ENDPOINTS, new OpEndpoint());
	}
}
