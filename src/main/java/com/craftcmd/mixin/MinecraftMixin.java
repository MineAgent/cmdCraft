/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Copyright (C) 2026 MineAgent
 */

package com.craftcmd.mixin;

import com.craftcmd.Jobs;
import com.craftcmd.MineJob;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drives the mod's multi-tick jobs and keeps vanilla input from cancelling them.
 *
 * <p>A craft job clicks real container slots and a mine job destroys a real block, so both have to
 * advance on the render thread and at most once per tick. The mod does not depend on Fabric API, so
 * instead of lifecycle events the client tick is injected directly at the end of {@code
 * Minecraft#tick}.</p>
 *
 * <p>{@code continueAttack} is cancelled while a {@link MineJob} runs: when the attack key is not
 * held, the vanilla path calls {@code MultiPlayerGameMode#stopDestroyBlock()} every tick, which would
 * send an abort packet and reset the mining progress the job just accumulated.</p>
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@Inject(method = "tick()V", at = @At("TAIL"))
	private void craftcmd$tick(CallbackInfo ci) {
		Jobs.tick((Minecraft) (Object) this);
	}

	@Inject(method = "continueAttack(Z)V", at = @At("HEAD"), cancellable = true)
	private void craftcmd$keepMining(boolean attacking, CallbackInfo ci) {
		if (MineJob.isRunning()) {
			ci.cancel();
		}
	}
}
