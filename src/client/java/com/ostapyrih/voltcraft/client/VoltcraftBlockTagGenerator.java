package com.ostapyrih.voltcraft.client;

import com.ostapyrih.voltcraft.block.VoltcraftBlocks;
import net.fabricmc.fabric.api.datagen.v1.FabricDataOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricTagProvider;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.registry.tag.BlockTags;

import java.util.concurrent.CompletableFuture;

/**
 * Generates vanilla block tags so tools interact correctly with VoltCraft blocks.
 */
public class VoltcraftBlockTagGenerator extends FabricTagProvider.BlockTagProvider {

    public VoltcraftBlockTagGenerator(FabricDataOutput output, CompletableFuture<RegistryWrapper.WrapperLookup> registriesFuture) {
        super(output, registriesFuture);
    }

    @Override
    protected void configure(RegistryWrapper.WrapperLookup wrapperLookup) {
        // Pickaxe mineable
        valueLookupBuilder(BlockTags.PICKAXE_MINEABLE)
            .add(VoltcraftBlocks.ORE_BAUXITE)
            .add(VoltcraftBlocks.DEEPSLATE_ORE_BAUXITE)
            .add(VoltcraftBlocks.ORE_GALENA)
            .add(VoltcraftBlocks.DEEPSLATE_ORE_GALENA)
            .add(VoltcraftBlocks.ORE_SPHALERITE)
            .add(VoltcraftBlocks.DEEPSLATE_ORE_SPHALERITE)
            .add(VoltcraftBlocks.ORE_SPODUMENE)
            .add(VoltcraftBlocks.DEEPSLATE_ORE_SPODUMENE)
            .add(VoltcraftBlocks.ORE_PENTLANDITE)
            .add(VoltcraftBlocks.DEEPSLATE_ORE_PENTLANDITE)
            .add(VoltcraftBlocks.ORE_HIGH_PURITY_QUARTZ)
            .add(VoltcraftBlocks.DEEPSLATE_ORE_QUARTZ)
            .add(VoltcraftBlocks.COPPER_BUSBAR)
            .add(VoltcraftBlocks.JUNCTION_BOX)
            .add(VoltcraftBlocks.KNIFE_SWITCH)
            .add(VoltcraftBlocks.CIRCUIT_BREAKER)
            .add(VoltcraftBlocks.FUSE_BOX)
            .add(VoltcraftBlocks.CONTACTOR_RELAY)
            .add(VoltcraftBlocks.BATTERY_BLOCK_LIFEPO4)
            .add(VoltcraftBlocks.BATTERY_BLOCK_LEAD_ACID)
            .add(VoltcraftBlocks.BATTERY_BLOCK_LTO)
            .add(VoltcraftBlocks.BATTERY_RACK_MODULAR)
            .add(VoltcraftBlocks.BATTERY_BLOCK_NIMH)
            .add(VoltcraftBlocks.BATTERY_BLOCK_NICD)
            .add(VoltcraftBlocks.CONVERTER_DC_BUCK)
            .add(VoltcraftBlocks.CONVERTER_DC_BOOST)
            .add(VoltcraftBlocks.CONVERTER_DC_BUCK_BOOST)
            .add(VoltcraftBlocks.REGULATOR_LINEAR_LDO)
            .add(VoltcraftBlocks.TRANSFORMER_AC_STEP_DOWN)
            .add(VoltcraftBlocks.TRANSFORMER_AC_STEP_UP)
            .add(VoltcraftBlocks.RECTIFIER_BRIDGE)
            .add(VoltcraftBlocks.RECTIFIER_ACTIVE_SYNCHRONOUS)
            .add(VoltcraftBlocks.INVERTER_SQUARE_WAVE)
            .add(VoltcraftBlocks.INVERTER_MODIFIED_SINE)
            .add(VoltcraftBlocks.INVERTER_PURE_SINE)
            .add(VoltcraftBlocks.INVERTER_GRID_TIE)
            .add(VoltcraftBlocks.INVERTER_HYBRID_ESS)
            .add(VoltcraftBlocks.CONVERTER_EU)
            .add(VoltcraftBlocks.SOLAR_PANEL_MONOCRYSTALLINE)
            .add(VoltcraftBlocks.SOLAR_PANEL_POLYCRYSTALLINE)
            .add(VoltcraftBlocks.SOLAR_PANEL_THIN_FILM)
            .add(VoltcraftBlocks.SOLAR_PANEL_CONCENTRATOR)
            .add(VoltcraftBlocks.CHARGE_CONTROLLER_MPPT)
            .add(VoltcraftBlocks.GENERATOR_HAND_CRANK)
            .add(VoltcraftBlocks.GENERATOR_PORTABLE_INVERTER)
            .add(VoltcraftBlocks.CREATIVE_GENERATOR)
            .add(VoltcraftBlocks.CREATIVE_LOAD);

        // Tool level requirements
        valueLookupBuilder(BlockTags.NEEDS_STONE_TOOL)
            .add(VoltcraftBlocks.ORE_BAUXITE)
            .add(VoltcraftBlocks.ORE_GALENA)
            .add(VoltcraftBlocks.ORE_SPHALERITE)
            .add(VoltcraftBlocks.ORE_SPODUMENE)
            .add(VoltcraftBlocks.COPPER_BUSBAR)
            .add(VoltcraftBlocks.JUNCTION_BOX)
            .add(VoltcraftBlocks.KNIFE_SWITCH)
            .add(VoltcraftBlocks.CIRCUIT_BREAKER)
            .add(VoltcraftBlocks.FUSE_BOX)
            .add(VoltcraftBlocks.CONTACTOR_RELAY)
            .add(VoltcraftBlocks.SOLAR_PANEL_MONOCRYSTALLINE)
            .add(VoltcraftBlocks.SOLAR_PANEL_POLYCRYSTALLINE)
            .add(VoltcraftBlocks.SOLAR_PANEL_THIN_FILM)
            .add(VoltcraftBlocks.SOLAR_PANEL_CONCENTRATOR)
            .add(VoltcraftBlocks.GENERATOR_HAND_CRANK);

        valueLookupBuilder(BlockTags.NEEDS_IRON_TOOL)
            .add(VoltcraftBlocks.DEEPSLATE_ORE_BAUXITE)
            .add(VoltcraftBlocks.DEEPSLATE_ORE_GALENA)
            .add(VoltcraftBlocks.DEEPSLATE_ORE_SPHALERITE)
            .add(VoltcraftBlocks.DEEPSLATE_ORE_SPODUMENE)
            .add(VoltcraftBlocks.ORE_PENTLANDITE)
            .add(VoltcraftBlocks.DEEPSLATE_ORE_PENTLANDITE)
            .add(VoltcraftBlocks.ORE_HIGH_PURITY_QUARTZ)
            .add(VoltcraftBlocks.DEEPSLATE_ORE_QUARTZ)
            .add(VoltcraftBlocks.BATTERY_BLOCK_LIFEPO4)
            .add(VoltcraftBlocks.BATTERY_BLOCK_LEAD_ACID)
            .add(VoltcraftBlocks.BATTERY_BLOCK_LTO)
            .add(VoltcraftBlocks.BATTERY_RACK_MODULAR)
            .add(VoltcraftBlocks.BATTERY_BLOCK_NIMH)
            .add(VoltcraftBlocks.BATTERY_BLOCK_NICD)
            .add(VoltcraftBlocks.CONVERTER_DC_BUCK)
            .add(VoltcraftBlocks.CONVERTER_DC_BOOST)
            .add(VoltcraftBlocks.CONVERTER_DC_BUCK_BOOST)
            .add(VoltcraftBlocks.REGULATOR_LINEAR_LDO)
            .add(VoltcraftBlocks.TRANSFORMER_AC_STEP_DOWN)
            .add(VoltcraftBlocks.TRANSFORMER_AC_STEP_UP)
            .add(VoltcraftBlocks.RECTIFIER_BRIDGE)
            .add(VoltcraftBlocks.RECTIFIER_ACTIVE_SYNCHRONOUS)
            .add(VoltcraftBlocks.INVERTER_SQUARE_WAVE)
            .add(VoltcraftBlocks.INVERTER_MODIFIED_SINE)
            .add(VoltcraftBlocks.INVERTER_PURE_SINE)
            .add(VoltcraftBlocks.INVERTER_GRID_TIE)
            .add(VoltcraftBlocks.INVERTER_HYBRID_ESS)
            .add(VoltcraftBlocks.CONVERTER_EU)
            .add(VoltcraftBlocks.CHARGE_CONTROLLER_MPPT)
            .add(VoltcraftBlocks.GENERATOR_PORTABLE_INVERTER);
    }
}
