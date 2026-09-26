package com.ostapyrih.voltcraft.client;

import com.ostapyrih.voltcraft.world.VoltcraftConfiguredFeatures;
import com.ostapyrih.voltcraft.world.VoltcraftPlacedFeatures;
import net.fabricmc.fabric.api.datagen.v1.DataGeneratorEntrypoint;
import net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator;
import net.minecraft.registry.RegistryBuilder;
import net.minecraft.registry.RegistryKeys;

public class VoltcraftDataGenerator implements DataGeneratorEntrypoint {

    @Override
    public void onInitializeDataGenerator(FabricDataGenerator fabricDataGenerator) {
        FabricDataGenerator.Pack pack = fabricDataGenerator.createPack();
        pack.addProvider(VoltcraftModelGenerator::new);
        pack.addProvider(VoltcraftWorldGenProvider::new);
        pack.addProvider(VoltcraftEnglishLanguageProvider::new);
        pack.addProvider(VoltcraftBlockLootTableGenerator::new);
        pack.addProvider(VoltcraftBlockTagGenerator::new);
        pack.addProvider(VoltcraftRecipeGenerator::new);
    }

    @Override
    public void buildRegistry(RegistryBuilder registryBuilder) {
        registryBuilder.addRegistry(RegistryKeys.CONFIGURED_FEATURE, VoltcraftConfiguredFeatures::bootstrap);
        registryBuilder.addRegistry(RegistryKeys.PLACED_FEATURE, VoltcraftPlacedFeatures::bootstrap);
    }
}
