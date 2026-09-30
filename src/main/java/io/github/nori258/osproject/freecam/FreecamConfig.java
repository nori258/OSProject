package io.github.nori258.osproject.freecam;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Settings for {@link FreecamModule}, stored as JSON in {@code config/osproject/freecam.json}.
 *
 * <p>Missing keys keep their defaults and unknown keys are ignored, so older files keep working
 * when options are added or removed. Out-of-range values are clamped by {@link #validate()}.
 */
public final class FreecamConfig {
	public static final float MIN_SPEED = 0.05F;
	public static final float MAX_SPEED = 10.0F;
	public static final float MIN_SPRINT_MULTIPLIER = 1.0F;
	public static final float MAX_SPRINT_MULTIPLIER = 10.0F;

	private static final float DEFAULT_HORIZONTAL_SPEED = 1.0F;
	private static final float DEFAULT_VERTICAL_SPEED = 0.8F;
	private static final float DEFAULT_SPRINT_MULTIPLIER = 2.0F;

	private static final Logger LOGGER = LoggerFactory.getLogger("OSProject/Freecam");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Horizontal flight speed in blocks per tick, {@value #MIN_SPEED} to {@value #MAX_SPEED}. Default 1.0. */
	public float horizontalSpeed = DEFAULT_HORIZONTAL_SPEED;
	/** Up/down (jump/sneak) speed in blocks per tick, {@value #MIN_SPEED} to {@value #MAX_SPEED}. Default 0.8. */
	public float verticalSpeed = DEFAULT_VERTICAL_SPEED;
	/** Multiplies both speeds while sprint is held, {@value #MIN_SPRINT_MULTIPLIER} to {@value #MAX_SPRINT_MULTIPLIER}. Default 2.0. */
	public float sprintMultiplier = DEFAULT_SPRINT_MULTIPLIER;
	/** Fly through blocks. When off, the camera collides with terrain using the player's hitbox. Default true. */
	public boolean noClip = true;
	/** Draw the player's body where it was left standing. Default true. */
	public boolean showPlayer = true;
	/** Draw the first-person hand and held item while flying. Default false. */
	public boolean showHand = false;
	/** Return to the player as soon as it takes damage. Default true. */
	public boolean disableOnDamage = true;
	/** Show an action-bar message when freecam turns on or off. Default true. */
	public boolean notifyOnToggle = true;

	/**
	 * Reads the config, falling back to defaults if the file is missing or unreadable, then writes
	 * it back so newly added options and clamped values show up in the file.
	 */
	public static FreecamConfig load(File file) {
		Path path = file.toPath();
		FreecamConfig config = null;
		if (Files.isRegularFile(path)) {
			try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				config = GSON.fromJson(reader, FreecamConfig.class);
			} catch (IOException | JsonParseException e) {
				LOGGER.warn("Could not read {}, using defaults", path, e);
				backUpUnreadable(path);
			}
		}
		if (config == null) {
			// Also covers an empty file, for which Gson returns null.
			config = new FreecamConfig();
		}
		config.save(file);
		return config;
	}

	/** Clamps the values (Gson refuses to write NaN), then writes the config, creating parent directories as needed. */
	public void save(File file) {
		validate();
		Path path = file.toPath().toAbsolutePath();
		Path temp = path.resolveSibling(path.getFileName() + ".tmp");
		try {
			Files.createDirectories(path.getParent());
			// Write beside the target and move it into place, so a crash mid-write can't leave a
			// truncated config behind.
			try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
				GSON.toJson(this, writer);
			}
			try {
				Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			LOGGER.error("Could not save {}", path, e);
		}
	}

	/** Clamps every numeric option into its valid range. */
	public FreecamConfig validate() {
		horizontalSpeed = clamp(horizontalSpeed, MIN_SPEED, MAX_SPEED, DEFAULT_HORIZONTAL_SPEED);
		verticalSpeed = clamp(verticalSpeed, MIN_SPEED, MAX_SPEED, DEFAULT_VERTICAL_SPEED);
		sprintMultiplier = clamp(sprintMultiplier, MIN_SPRINT_MULTIPLIER, MAX_SPRINT_MULTIPLIER, DEFAULT_SPRINT_MULTIPLIER);
		return this;
	}

	private static float clamp(float value, float min, float max, float fallback) {
		// NaN fails every comparison, so Math.min/max would pass it straight through.
		if (Float.isNaN(value)) {
			return fallback;
		}
		return Math.max(min, Math.min(max, value));
	}

	private static void backUpUnreadable(Path path) {
		// Keep the broken file for the user to look at instead of silently overwriting it.
		Path backup = path.resolveSibling(path.getFileName() + ".broken");
		try {
			Files.move(path, backup, StandardCopyOption.REPLACE_EXISTING);
			LOGGER.warn("Moved unreadable config to {}", backup);
		} catch (IOException e) {
			LOGGER.error("Could not back up {}", path, e);
		}
	}
}
