package io.github.nori258.osproject.freecam;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * The entity the camera rides while freecam is on.
 *
 * <p>It exists only on this client and is never added to the level, so vanilla never ticks,
 * renders, ray-traces or syncs it, and nothing can target it. {@link FreecamModule} moves it once
 * per tick and routes mouse look into it.
 */
final class FreecamGhostEntity extends ArmorStand {
	// Server-assigned ids are positive, so a negative one can never alias a real entity.
	private static final int GHOST_ENTITY_ID = -1_000_000;

	// Read by overridden methods that may run during the super constructor, before these are
	// assigned: the zero defaults are harmless because refreshDimensions() runs again once they're
	// set, and the effect lookups fall back to super while player is still null.
	private final LocalPlayer player;
	private float ghostWidth;
	private float ghostHeight;
	private float ghostEyeHeight;

	FreecamGhostEntity(ClientLevel level, LocalPlayer player) {
		super(EntityType.ARMOR_STAND, level);
		this.player = player;
		setId(GHOST_ENTITY_ID);
		// Nothing ticks or renders the ghost, but stay inert if some other mod ever does.
		setNoGravity(true);
		setInvisible(true);
		setSilent(true);

		// Copy the player's hitbox and eye height. Camera eases toward the focused entity's eye
		// height every tick, so a mismatch would make the view drift right after switching, and
		// collision mode should fit exactly where the player fits.
		ghostWidth = player.getBbWidth();
		ghostHeight = player.getBbHeight();
		ghostEyeHeight = player.getEyeHeight();
		refreshDimensions();

		Vec3 eye = player.getEyePosition();
		moveTo(eye.x, eye.y - getEyeHeight(), eye.z, player.getYRot(), player.getXRot());
		setOldPosAndRot();
	}

	@Override
	public EntityDimensions getDefaultDimensions(Pose pose) {
		return EntityDimensions.fixed(ghostWidth, ghostHeight).withEyeHeight(ghostEyeHeight);
	}

	@Override
	public float getViewYRot(float partialTick) {
		// LivingEntity reports its head yaw here, but Entity#turn (mouse look) only rotates the
		// body, and the camera reads this method. Report the body yaw so turning actually works.
		return partialTick == 1.0F ? getYRot() : Mth.lerp(partialTick, yRotO, getYRot());
	}

	// FogRenderer takes blindness/darkness (and night-vision fog colour) from the camera entity, so
	// answer with the player's effects; otherwise freecam would be a way to see while blinded.
	// Both must be delegated together: callers check hasEffect() and then read getEffect().
	@Override
	public boolean hasEffect(Holder<MobEffect> effect) {
		return player != null ? player.hasEffect(effect) : super.hasEffect(effect);
	}

	@Override
	public MobEffectInstance getEffect(Holder<MobEffect> effect) {
		return player != null ? player.getEffect(effect) : super.getEffect(effect);
	}

	/**
	 * Moves the ghost by one tick's worth of motion. The old position is recorded first so the
	 * camera interpolates smoothly between ticks.
	 */
	void fly(Vec3 motion, boolean noClip) {
		setOldPosAndRot();
		if (motion.lengthSqr() < 1.0E-7) {
			return;
		}
		if (!noClip) {
			// Collision only: unlike Entity#move this doesn't set off block effects (redstone ore,
			// cobwebs, ...) on the client for an entity that isn't really there.
			motion = Entity.collideBoundingBox(this, motion, getBoundingBox(), level(), List.of());
		}
		setPos(getX() + motion.x, getY() + motion.y, getZ() + motion.z);
	}
}
