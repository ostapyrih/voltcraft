package com.ostapyrih.voltcraft.world;

import com.ostapyrih.voltcraft.Voltcraft;
import com.ostapyrih.voltcraft.block.VoltcraftBlocks;
import net.minecraft.registry.Registerable;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.structure.rule.RuleTest;
import net.minecraft.structure.rule.TagMatchRuleTest;
import net.minecraft.util.Identifier;
import net.minecraft.world.gen.feature.ConfiguredFeature;
import net.minecraft.world.gen.feature.ConfiguredFeatures;
import net.minecraft.world.gen.feature.Feature;
import net.minecraft.world.gen.feature.OreFeatureConfig;

import java.util.List;

/**
 * Registry keys and bootstrap configuration for VoltCraft configured ore vein features.
 */
public class VoltcraftConfiguredFeatures {

    public static final RegistryKey<ConfiguredFeature<?, ?>> ORE_BAUXITE = registerKey("ore_bauxite");
    public static final RegistryKey<ConfiguredFeature<?, ?>> ORE_GALENA = registerKey("ore_galena");
    public static final RegistryKey<ConfiguredFeature<?, ?>> ORE_SPHALERITE = registerKey("ore_sphalerite");
    public static final RegistryKey<ConfiguredFeature<?, ?>> ORE_SPODUMENE = registerKey("ore_spodumene");
    public static final RegistryKey<ConfiguredFeature<?, ?>> ORE_PENTLANDITE = registerKey("ore_pentlandite");
    public static final RegistryKey<ConfiguredFeature<?, ?>> ORE_HIGH_PURITY_QUARTZ = registerKey("ore_high_purity_quartz");

    private static RegistryKey<ConfiguredFeature<?, ?>> registerKey(String name) {
        return RegistryKey.of(RegistryKeys.CONFIGURED_FEATURE, Identifier.of(Voltcraft.MOD_ID, name));
    }

    public static void bootstrap(Registerable<ConfiguredFeature<?, ?>> context) {
        RuleTest stoneReplaceables = new TagMatchRuleTest(BlockTags.STONE_ORE_REPLACEABLES);
        RuleTest deepslateReplaceables = new TagMatchRuleTest(BlockTags.DEEPSLATE_ORE_REPLACEABLES);

        // 1. Bauxite (Aluminum) - Vein size 8
        List<OreFeatureConfig.Target> bauxiteTargets = List.of(
            OreFeatureConfig.createTarget(stoneReplaceables, VoltcraftBlocks.ORE_BAUXITE.getDefaultState()),
            OreFeatureConfig.createTarget(deepslateReplaceables, VoltcraftBlocks.DEEPSLATE_ORE_BAUXITE.getDefaultState())
        );
        ConfiguredFeatures.register(context, ORE_BAUXITE, Feature.ORE, new OreFeatureConfig(bauxiteTargets, 8));

        // 2. Galena (Lead/Silver) - Vein size 7
        List<OreFeatureConfig.Target> galenaTargets = List.of(
            OreFeatureConfig.createTarget(stoneReplaceables, VoltcraftBlocks.ORE_GALENA.getDefaultState()),
            OreFeatureConfig.createTarget(deepslateReplaceables, VoltcraftBlocks.DEEPSLATE_ORE_GALENA.getDefaultState())
        );
        ConfiguredFeatures.register(context, ORE_GALENA, Feature.ORE, new OreFeatureConfig(galenaTargets, 7));

        // 3. Sphalerite (Zinc) - Vein size 6
        List<OreFeatureConfig.Target> sphaleriteTargets = List.of(
            OreFeatureConfig.createTarget(stoneReplaceables, VoltcraftBlocks.ORE_SPHALERITE.getDefaultState()),
            OreFeatureConfig.createTarget(deepslateReplaceables, VoltcraftBlocks.DEEPSLATE_ORE_SPHALERITE.getDefaultState())
        );
        ConfiguredFeatures.register(context, ORE_SPHALERITE, Feature.ORE, new OreFeatureConfig(sphaleriteTargets, 6));

        // 4. Spodumene (Lithium) - Vein size 5
        List<OreFeatureConfig.Target> spodumeneTargets = List.of(
            OreFeatureConfig.createTarget(stoneReplaceables, VoltcraftBlocks.ORE_SPODUMENE.getDefaultState()),
            OreFeatureConfig.createTarget(deepslateReplaceables, VoltcraftBlocks.DEEPSLATE_ORE_SPODUMENE.getDefaultState())
        );
        ConfiguredFeatures.register(context, ORE_SPODUMENE, Feature.ORE, new OreFeatureConfig(spodumeneTargets, 5));

        // 5. Pentlandite (Nickel) - Vein size 6
        List<OreFeatureConfig.Target> pentlanditeTargets = List.of(
            OreFeatureConfig.createTarget(stoneReplaceables, VoltcraftBlocks.ORE_PENTLANDITE.getDefaultState()),
            OreFeatureConfig.createTarget(deepslateReplaceables, VoltcraftBlocks.DEEPSLATE_ORE_PENTLANDITE.getDefaultState())
        );
        ConfiguredFeatures.register(context, ORE_PENTLANDITE, Feature.ORE, new OreFeatureConfig(pentlanditeTargets, 6));

        // 6. High-Purity Quartz (Silica) - Vein size 6
        List<OreFeatureConfig.Target> quartzTargets = List.of(
            OreFeatureConfig.createTarget(stoneReplaceables, VoltcraftBlocks.ORE_HIGH_PURITY_QUARTZ.getDefaultState()),
            OreFeatureConfig.createTarget(deepslateReplaceables, VoltcraftBlocks.DEEPSLATE_ORE_QUARTZ.getDefaultState())
        );
        ConfiguredFeatures.register(context, ORE_HIGH_PURITY_QUARTZ, Feature.ORE, new OreFeatureConfig(quartzTargets, 6));
    }
}
