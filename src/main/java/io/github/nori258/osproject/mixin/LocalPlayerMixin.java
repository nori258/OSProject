package io.github.nori258.osproject.mixin;

import io.github.nori258.osproject.freecam.FreecamModule;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {
	// A LocalPlayer that isn't the camera entity stops reading its input and stops sending movement
	// packets (that's how spectating a mob works). Left like that, the frozen player would coast on
	// its last input and go silent to the server. Keep it behaving like a normal idle player.
	@Inject(method = "isControlledCamera", at = @At("HEAD"), cancellable = true)
	private void osproject$stayInControl(CallbackInfoReturnable<Boolean> cir) {
		if (FreecamModule.keepsPlayerInControl((LocalPlayer) (Object) this)) {
			cir.setReturnValue(true);
		}
	}
}
