package io.github.nori258.osproject.mixin;

import io.github.nori258.osproject.freecam.FreecamModule;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Gui.class)
public abstract class GuiMixin {
	// The HUD follows the camera entity and hides the hotbar, health and hunger when that isn't a
	// player. Keep showing the frozen player's, since that's the body that can still get hurt.
	@Inject(method = "getCameraPlayer", at = @At("HEAD"), cancellable = true)
	private void osproject$showFrozenPlayerHud(CallbackInfoReturnable<Player> cir) {
		LocalPlayer player = FreecamModule.hudPlayer();
		if (player != null) {
			cir.setReturnValue(player);
		}
	}
}
