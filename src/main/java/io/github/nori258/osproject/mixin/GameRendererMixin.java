package io.github.nori258.osproject.mixin;

import io.github.nori258.osproject.freecam.FreecamModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	// pick() ray-traces from the camera entity. From the ghost that would target blocks and mobs
	// next to the camera, which also drives held-down block breaking, so report a miss instead.
	@Inject(method = "pick(F)V", at = @At("HEAD"), cancellable = true)
	private void osproject$noTargetFromFreecam(float partialTicks, CallbackInfo ci) {
		if (FreecamModule.overrideCrosshairTarget(Minecraft.getInstance())) {
			ci.cancel();
		}
	}
}
