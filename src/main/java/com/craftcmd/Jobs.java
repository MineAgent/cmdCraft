/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Copyright (C) 2026 MineAgent
 */

package com.craftcmd;

import net.minecraft.client.Minecraft;

/**
 * The long running operations of the mod, driven once per client tick.
 *
 * <p>All three jobs drive vanilla player actions over several ticks ({@link CraftJob} clicks the
 * crafting grid, {@link EatJob} keeps an item in use, {@link MineJob} keeps destroying a block). They
 * share the same Minecraft state, so only one of them may run at a time; commands check
 * {@link #isBusy()} before they start a job.</p>
 *
 * <p>The tick is injected by {@code MinecraftMixin}, because the mod does not depend on Fabric API.</p>
 */
public final class Jobs {
	private Jobs() {
	}

	/** @return true while any multi-tick command owns the client */
	public static boolean isBusy() {
		return CraftJob.isRunning() || EatJob.isRunning() || MineJob.isRunning();
	}

	/** Advances whichever job is running; called once per client tick. */
	public static void tick(Minecraft client) {
		CraftJob.tick(client);
		EatJob.tick(client);
		MineJob.tick(client);
	}
}
