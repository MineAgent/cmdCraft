/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Copyright (C) 2026 MineAgent
 */

package com.craftcmd.mixin;

import com.craftcmd.EatJob;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps a mod-driven item use alive.
 *
 * <p>{@code Minecraft#handleKeybinds} releases the item in use on every tick in which the use key is
 * not physically held down. An {@link EatJob} is issued without any key press, so without this
 * injection the client would send {@code RELEASE_USE_ITEM} one tick after the job started the use and
 * the server would never finish eating. The job clears its active flag before it deliberately
 * releases an item, so real releases (aborts) still go through.</p>
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {
	@Inject(method = "releaseUsingItem(Lnet/minecraft/world/entity/player/Player;)V", at = @At("HEAD"),
			cancellable = true)
	private void craftcmd$keepUsingItem(Player player, CallbackInfo ci) {
		if (EatJob.suppressesRelease()) {
			ci.cancel();
		}
	}
}
