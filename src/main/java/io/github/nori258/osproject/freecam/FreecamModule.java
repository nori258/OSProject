package io.github.nori258.osproject.freecam;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.io.File;

/**
 * Freecam: detaches the camera from the player so it can fly around, through blocks by default.
 *
 * <p>The camera moves onto a client-only {@link FreecamGhostEntity}. The real player stays a
 * normal, fully simulated entity: its movement input is swapped for one that never presses
 * anything, so it just stands there and keeps sending the same idle updates as any AFK player.
 * Nothing the server sees is faked. Leaving freecam hands the camera back to the player, which is
 * still exactly where it was left.
 *
 * <p>All state is touched on the render thread only (tick events, render events and mixins).
 */
public final class FreecamModule {
	private static FreecamConfig config = new FreecamConfig();
	private static KeyMapping toggleKey;

	// Session state; the references are all null while freecam is off.
	private static FreecamGhostEntity ghost;
	private static LocalPlayer frozenPlayer;
	private static Input realInput;
	private static Input stillInput;
	private static CameraType previousCameraType;
	private static boolean previousSmartCull;
	private static int lastHurtTime;

	private FreecamModule() {
	}

	public static void init(File configFile) {
		config = FreecamConfig.load(configFile);
		toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.osproject.freecam",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_F6,
				"key.categories.osproject"));

		ClientTickEvents.START_CLIENT_TICK.register(FreecamModule::onStartTick);
		ClientTickEvents.END_CLIENT_TICK.register(FreecamModule::onEndTick);
		WorldRenderEvents.AFTER_ENTITIES.register(FreecamModule::renderFrozenPlayer);
	}

	public static boolean isActive() {
		return ghost != null;
	}

	// ---- Hooks for the mixins ----

	/**
	 * Mouse look aimed at the frozen player turns the camera instead.
	 *
	 * @return true if the turn was redirected and vanilla should skip it
	 */
	public static boolean redirectTurn(Entity entity, double yRot, double xRot) {
		FreecamGhostEntity camera = ghost;
		if (camera == null || entity != frozenPlayer) {
			return false;
		}
		camera.turn(yRot, xRot);
		return true;
	}

	/** Whether the player should keep acting as its own camera for input and movement packets. */
	public static boolean keepsPlayerInControl(LocalPlayer player) {
		return ghost != null && player == frozenPlayer;
	}

	/**
	 * Replaces the crosshair ray-trace with a miss, so nothing can be targeted from the camera.
	 *
	 * @return true if the target was set and vanilla's ray-trace should be skipped
	 */
	public static boolean overrideCrosshairTarget(Minecraft client) {
		FreecamGhostEntity camera = ghost;
		if (camera == null) {
			return false;
		}
		Vec3 eye = camera.getEyePosition();
		client.hitResult = BlockHitResult.miss(eye, Direction.UP, BlockPos.containing(eye));
		client.crosshairPickEntity = null;
		return true;
	}

	/** The player whose hotbar, health and hunger the HUD should show, or null to let vanilla decide. */
	public static LocalPlayer hudPlayer() {
		return ghost != null ? frozenPlayer : null;
	}

	// ---- Ticking ----

	private static void onStartTick(Minecraft client) {
		FreecamGhostEntity camera = ghost;
		if (camera == null) {
			return;
		}
		if (!isSessionIntact(client, camera)) {
			exit(client, null);
			return;
		}
		// Third person would only orbit an invisible armor stand, so drop F5 presses while flying.
		while (client.options.keyTogglePerspective.consumeClick()) {
			// discard
		}
	}

	private static void onEndTick(Minecraft client) {
		while (toggleKey.consumeClick()) {
			if (ghost != null) {
				exit(client, "message.osproject.freecam.disabled");
			} else {
				enter(client);
			}
		}

		FreecamGhostEntity camera = ghost;
		if (camera == null) {
			return;
		}
		if (!isSessionIntact(client, camera)) {
			exit(client, null);
			return;
		}

		LocalPlayer player = client.player;
		if (player.isDeadOrDying()) {
			exit(client, null);
			return;
		}
		// hurtTime jumps back to its maximum on every hit and otherwise only counts down.
		if (config.disableOnDamage && player.hurtTime > lastHurtTime) {
			exit(client, "message.osproject.freecam.damaged");
			return;
		}
		lastHurtTime = player.hurtTime;

		// If something swapped in a fresh input (another mod, say), adopt it as the one to restore
		// on exit and put the still input back.
		if (player.input != stillInput) {
			realInput = player.input;
			player.input = stillInput;
		}
		if (!client.options.getCameraType().isFirstPerson()) {
			client.options.setCameraType(CameraType.FIRST_PERSON);
		}

		Vec3 motion = client.screen == null ? readMotion(client.options, camera.getYRot()) : Vec3.ZERO;
		camera.fly(motion, config.noClip);
	}

	private static boolean isSessionIntact(Minecraft client, FreecamGhostEntity camera) {
		// Respawning and changing dimension replace the LocalPlayer and ClientLevel, and the server
		// can take over the camera itself (spectator mode). Any of those ends the session.
		return client.player != null
				&& client.player == frozenPlayer
				&& client.level != null
				&& camera.level() == client.level
				&& client.getCameraEntity() == camera;
	}

	/** Turns the held movement keys into this tick's motion, relative to where the camera faces. */
	private static Vec3 readMotion(Options options, float yaw) {
		float forward = axis(options.keyUp, options.keyDown);
		float strafe = axis(options.keyLeft, options.keyRight);
		float vertical = axis(options.keyJump, options.keyShift);
		double boost = options.keySprint.isDown() ? config.sprintMultiplier : 1.0;

		double x = 0.0;
		double z = 0.0;
		double length = Math.sqrt(forward * forward + strafe * strafe);
		if (length > 0.0) {
			// Normalise so diagonals aren't faster, then rotate by yaw the same way vanilla walking
			// does (Entity#getInputVector). Positive strafe is to the left.
			double scale = config.horizontalSpeed * boost / Math.max(1.0, length);
			double yawRad = Math.toRadians(yaw);
			double sin = Math.sin(yawRad);
			double cos = Math.cos(yawRad);
			x = (strafe * cos - forward * sin) * scale;
			z = (forward * cos + strafe * sin) * scale;
		}
		return new Vec3(x, vertical * config.verticalSpeed * boost, z);
	}

	private static float axis(KeyMapping positive, KeyMapping negative) {
		return (positive.isDown() ? 1.0F : 0.0F) - (negative.isDown() ? 1.0F : 0.0F);
	}

	// ---- Entering and leaving ----

	private static void enter(Minecraft client) {
		LocalPlayer player = client.player;
		if (player == null || client.level == null) {
			return;
		}
		// Already looking through another entity (spectating a mob): leave that camera alone.
		if (client.getCameraEntity() != player) {
			return;
		}

		FreecamGhostEntity camera = new FreecamGhostEntity(client.level, player);

		Input still = new Input();
		// Keep a crouching player crouched, so its pose doesn't change while it's left alone.
		still.shiftKeyDown = player.input.shiftKeyDown;
		realInput = player.input;
		stillInput = still;
		player.input = still;
		frozenPlayer = player;
		lastHurtTime = player.hurtTime;

		// Don't leave a half-mined block hanging on the server.
		if (client.gameMode != null) {
			client.gameMode.stopDestroyBlock();
		}

		previousCameraType = client.options.getCameraType();
		client.options.setCameraType(CameraType.FIRST_PERSON);
		previousSmartCull = client.smartCull;
		if (config.noClip) {
			// Smart culling skips chunk sections it thinks are hidden behind solid blocks, which
			// blanks out the world once the camera is inside terrain. Vanilla turns it off for
			// spectators inside blocks for the same reason.
			client.smartCull = false;
		}
		client.gameRenderer.setRenderHand(config.showHand);

		ghost = camera;
		client.setCameraEntity(camera);
		notify(player, "message.osproject.freecam.enabled");
	}

	/** Ends the session, if any. {@code messageKey} is the action-bar message to show, or null for none. */
	private static void exit(Minecraft client, String messageKey) {
		FreecamGhostEntity camera = ghost;
		if (camera == null) {
			return;
		}
		ghost = null;

		LocalPlayer player = client.player;
		boolean samePlayer = player != null && player == frozenPlayer;
		if (samePlayer && player.input == stillInput && realInput != null) {
			player.input = realInput;
		}
		// Only hand the camera back if it's still ours; the server may have moved it elsewhere.
		// With no player left (disconnected), null is fine: vanilla re-targets it on the next frame.
		if (client.getCameraEntity() == camera) {
			client.setCameraEntity(player);
		}
		if (previousCameraType != null) {
			client.options.setCameraType(previousCameraType);
		}
		client.smartCull = previousSmartCull;
		client.gameRenderer.setRenderHand(true);

		frozenPlayer = null;
		realInput = null;
		stillInput = null;
		previousCameraType = null;

		if (samePlayer && messageKey != null) {
			notify(player, messageKey);
		}
	}

	private static void notify(LocalPlayer player, String messageKey) {
		if (config.notifyOnToggle) {
			player.displayClientMessage(Component.translatable(messageKey), true);
		}
	}

	// ---- Rendering ----

	/**
	 * Draws the frozen player. Vanilla skips the LocalPlayer whenever it isn't the camera entity
	 * (that's what hides your own body while spectating a mob), so without this the player would
	 * be invisible from the free camera.
	 */
	private static void renderFrozenPlayer(WorldRenderContext context) {
		LocalPlayer player = frozenPlayer;
		if (ghost == null || player == null || !config.showPlayer) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		if (client.player != player || client.getCameraEntity() != ghost) {
			return;
		}

		EntityRenderDispatcher dispatcher = client.getEntityRenderDispatcher();
		Vec3 cameraPos = context.camera().getPosition();
		Frustum frustum = context.frustum();
		if (frustum != null && !dispatcher.shouldRender(player, frustum, cameraPos.x, cameraPos.y, cameraPos.z)) {
			return;
		}

		// Same interpolation as LevelRenderer#renderEntity.
		float partialTick = context.tickCounter().getGameTimeDeltaPartialTick(true);
		double x = Mth.lerp(partialTick, player.xOld, player.getX()) - cameraPos.x;
		double y = Mth.lerp(partialTick, player.yOld, player.getY()) - cameraPos.y;
		double z = Mth.lerp(partialTick, player.zOld, player.getZ()) - cameraPos.z;
		float yaw = Mth.lerp(partialTick, player.yRotO, player.getYRot());

		MultiBufferSource buffers = context.consumers();
		if (buffers == null) {
			buffers = client.renderBuffers().bufferSource();
		}
		// Vanilla's entity pass uses an identity pose stack: the view rotation is already on
		// RenderSystem's model-view stack at this point, so adding it here would apply it twice.
		dispatcher.render(player, x, y, z, yaw, partialTick, new PoseStack(), buffers,
				dispatcher.getPackedLightCoords(player, partialTick));
	}
}
