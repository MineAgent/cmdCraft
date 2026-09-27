/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Copyright (C) 2026 MineAgent
 */

package com.craftcmd.mixin;

import com.craftcmd.CraftJob;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drives {@link CraftJob} from the client tick.
 *
 * <p>A craft job clicks real container slots, so it has to advance on the render thread and at most
 * once per tick (the server needs a tick to compute the crafting result and to answer the click).
 * The mod does not depend on Fabric API, so instead of a lifecycle event the tick is injected
 * directly at the end of {@code Minecraft#tick}.</p>
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@Inject(method = "tick()V", at = @At("TAIL"))
	private void craftcmd$tick(CallbackInfo ci) {
		CraftJob.tick((Minecraft) (Object) this);
	}
}
