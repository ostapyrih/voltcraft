package com.ostapyrih.voltcraft.world;

import com.ostapyrih.voltcraft.Voltcraft;
import net.minecraft.registry.Registerable;
import net.minecraft.registry.RegistryEntryLookup;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;
import net.minecraft.world.gen.YOffset;
import net.minecraft.world.gen.feature.ConfiguredFeature;
import net.minecraft.world.gen.feature.PlacedFeature;
import net.minecraft.world.gen.placementmodifier.*;

import java.util.List;

/**
 * Registry keys and bootstrap placement modifiers for VoltCraft ore veins.
 */
public class VoltcraftPlacedFeatures {

    public static final RegistryKey<PlacedFeature> ORE_BAUXITE_PLACED = registerKey("ore_bauxite_placed");
    public static final RegistryKey<PlacedFeature> ORE_GALENA_PLACED = registerKey("ore_galena_placed");
    public static final RegistryKey<PlacedFeature> ORE_SPHALERITE_PLACED = registerKey("ore_sphalerite_placed");
    public static final RegistryKey<PlacedFeature> ORE_SPODUMENE_PLACED = registerKey("ore_spodumene_placed");
    public static final RegistryKey<PlacedFeature> ORE_PENTLANDITE_PLACED = registerKey("ore_pentlandite_placed");
    public static final RegistryKey<PlacedFeature> ORE_HIGH_PURITY_QUARTZ_PLACED = registerKey("ore_high_purity_quartz_placed");

    private static RegistryKey<PlacedFeature> registerKey(String name) {
        return RegistryKey.of(RegistryKeys.PLACED_FEATURE, Identifier.of(Voltcraft.MOD_ID, name));
    }

    public static void bootstrap(Registerable<PlacedFeature> context) {
        RegistryEntryLookup<ConfiguredFeature<?, ?>> lookup = context.getRegistryLookup(RegistryKeys.CONFIGURED_FEATURE);

        // Bauxite: High overworld Y=32..128 (Trapezoid peak)
        register(context, ORE_BAUXITE_PLACED, lookup.getOrThrow(VoltcraftConfiguredFeatures.ORE_BAUXITE),
            modifiersWithCount(6, HeightRangePlacementModifier.trapezoid(YOffset.fixed(32), YOffset.fixed(128))));

        // Galena: Deep crust to shallow Y=-64..16
        register(context, ORE_GALENA_PLACED, lookup.getOrThrow(VoltcraftConfiguredFeatures.ORE_GALENA),
            modifiersWithCount(5, HeightRangePlacementModifier.uniform(YOffset.fixed(-64), YOffset.fixed(16))));

        // Sphalerite: River/plains Y=-16..48
        register(context, ORE_SPHALERITE_PLACED, lookup.getOrThrow(VoltcraftConfiguredFeatures.ORE_SPHALERITE),
            modifiersWithCount(5, HeightRangePlacementModifier.uniform(YOffset.fixed(-16), YOffset.fixed(48))));

        // Spodumene (Lithium): Deep crust pegmatites Y=-64..8
        register(context, ORE_SPODUMENE_PLACED, lookup.getOrThrow(VoltcraftConfiguredFeatures.ORE_SPODUMENE),
            modifiersWithCount(4, HeightRangePlacementModifier.uniform(YOffset.fixed(-64), YOffset.fixed(8))));

        // Pentlandite (Nickel): Deep crust Y=-56..0
        register(context, ORE_PENTLANDITE_PLACED, lookup.getOrThrow(VoltcraftConfiguredFeatures.ORE_PENTLANDITE),
            modifiersWithCount(4, HeightRangePlacementModifier.uniform(YOffset.fixed(-56), YOffset.fixed(0))));

        // High-Purity Quartz: Y=-32..64
        register(context, ORE_HIGH_PURITY_QUARTZ_PLACED, lookup.getOrThrow(VoltcraftConfiguredFeatures.ORE_HIGH_PURITY_QUARTZ),
            modifiersWithCount(5, HeightRangePlacementModifier.uniform(YOffset.fixed(-32), YOffset.fixed(64))));
    }

    private static List<PlacementModifier> modifiersWithCount(int count, PlacementModifier heightModifier) {
        return List.of(
            CountPlacementModifier.of(count),
            SquarePlacementModifier.of(),
            heightModifier,
            BiomePlacementModifier.of()
        );
    }

    private static void register(Registerable<PlacedFeature> context, RegistryKey<PlacedFeature> key,
                                 RegistryEntry<ConfiguredFeature<?, ?>> configuration, List<PlacementModifier> modifiers) {
        context.register(key, new PlacedFeature(configuration, modifiers));
    }
}
