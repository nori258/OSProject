package io.github.nori258.osproject.mixin;

import io.github.nori258.osproject.freecam.FreecamModule;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	// The player can't reach anything near the camera, so attacking or using items while flying
	// would only swing the arm or fire off actions in the wrong direction. Block both.
	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void osproject$blockAttackInFreecam(CallbackInfoReturnable<Boolean> cir) {
		if (FreecamModule.isActive()) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void osproject$blockUseInFreecam(CallbackInfo ci) {
		if (FreecamModule.isActive()) {
			ci.cancel();
		}
	}
}
