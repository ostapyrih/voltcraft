package com.ostapyrih.voltcraft.world;

import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.minecraft.world.gen.GenerationStep;

/**
 * Injects VoltCraft mineral ores into the Overworld biome generation pipeline.
 */
public class VoltcraftBiomeModifications {

    public static void initialize() {
        // Bauxite: High overworld Y=32..128
        BiomeModifications.addFeature(
            BiomeSelectors.foundInOverworld(),
            GenerationStep.Feature.UNDERGROUND_ORES,
            VoltcraftPlacedFeatures.ORE_BAUXITE_PLACED
        );

        // Galena: Deep crust to shallow Y=-64..16
        BiomeModifications.addFeature(
            BiomeSelectors.foundInOverworld(),
            GenerationStep.Feature.UNDERGROUND_ORES,
            VoltcraftPlacedFeatures.ORE_GALENA_PLACED
        );

        // Sphalerite: River/plains Y=-16..48
        BiomeModifications.addFeature(
            BiomeSelectors.foundInOverworld(),
            GenerationStep.Feature.UNDERGROUND_ORES,
            VoltcraftPlacedFeatures.ORE_SPHALERITE_PLACED
        );

        // Spodumene (Lithium): Deep crust pegmatites Y=-64..8
        BiomeModifications.addFeature(
            BiomeSelectors.foundInOverworld(),
            GenerationStep.Feature.UNDERGROUND_ORES,
            VoltcraftPlacedFeatures.ORE_SPODUMENE_PLACED
        );

        // Pentlandite (Nickel): Deep crust Y=-56..0
        BiomeModifications.addFeature(
            BiomeSelectors.foundInOverworld(),
            GenerationStep.Feature.UNDERGROUND_ORES,
            VoltcraftPlacedFeatures.ORE_PENTLANDITE_PLACED
        );

        // High-Purity Quartz: Y=-32..64
        BiomeModifications.addFeature(
            BiomeSelectors.foundInOverworld(),
            GenerationStep.Feature.UNDERGROUND_ORES,
            VoltcraftPlacedFeatures.ORE_HIGH_PURITY_QUARTZ_PLACED
        );
    }
}
