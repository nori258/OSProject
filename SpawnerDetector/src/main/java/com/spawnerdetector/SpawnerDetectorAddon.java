package com.spawnerdetector;

import com.spawnerdetector.modules.SpawnerDetector;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.item.Items;

public class SpawnerDetectorAddon extends MeteorAddon {
    public static final Category CATEGORY = new Category("Spawner Detector", Items.SPAWNER.getDefaultStack());

    @Override
    public void onInitialize() {
        Modules.get().add(new SpawnerDetector());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.spawnerdetector";
    }
}
