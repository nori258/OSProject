package io.github.nori258.osproject.mixin;

import io.github.nori258.osproject.freecam.FreecamModule;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntityMixin {
	// MouseHandler always turns the player; while freecam is on, turn the camera instead. Hooked
	// here rather than in MouseHandler so it covers every caller of Entity#turn for the player.
	@Inject(method = "turn", at = @At("HEAD"), cancellable = true)
	private void osproject$turnFreecamInstead(double yRot, double xRot, CallbackInfo ci) {
		if (FreecamModule.redirectTurn((Entity) (Object) this, yRot, xRot)) {
			ci.cancel();
		}
	}
}
