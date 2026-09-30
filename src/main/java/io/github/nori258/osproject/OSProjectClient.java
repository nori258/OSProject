package io.github.nori258.osproject;

import io.github.nori258.osproject.freecam.FreecamModule;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

public final class OSProjectClient implements ClientModInitializer {
	public static final String MOD_ID = "osproject";

	@Override
	public void onInitializeClient() {
		Path configDir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
		FreecamModule.init(configDir.resolve("freecam.json").toFile());
	}
}
