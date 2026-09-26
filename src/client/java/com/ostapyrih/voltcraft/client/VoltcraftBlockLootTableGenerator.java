package com.ostapyrih.voltcraft.client;

import com.ostapyrih.voltcraft.block.VoltcraftBlocks;
import com.ostapyrih.voltcraft.item.VoltcraftItems;
import net.fabricmc.fabric.api.datagen.v1.FabricDataOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricBlockLootTableProvider;
import net.minecraft.registry.RegistryWrapper;

import java.util.concurrent.CompletableFuture;

/**
 * Generates loot tables so all VoltCraft blocks drop themselves or appropriate raw ores when mined.
 */
public class VoltcraftBlockLootTableGenerator extends FabricBlockLootTableProvider {

    public VoltcraftBlockLootTableGenerator(FabricDataOutput dataOutput, CompletableFuture<RegistryWrapper.WrapperLookup> registryLookup) {
        super(dataOutput, registryLookup);
    }

    @Override
    public void generate() {
        // Ores drop raw ore items (with Silk Touch dropping the ore block)
        addDrop(VoltcraftBlocks.ORE_BAUXITE, oreDrops(VoltcraftBlocks.ORE_BAUXITE, VoltcraftItems.RAW_BAUXITE));
        addDrop(VoltcraftBlocks.DEEPSLATE_ORE_BAUXITE, oreDrops(VoltcraftBlocks.DEEPSLATE_ORE_BAUXITE, VoltcraftItems.RAW_BAUXITE));
        addDrop(VoltcraftBlocks.ORE_GALENA, oreDrops(VoltcraftBlocks.ORE_GALENA, VoltcraftItems.RAW_GALENA));
        addDrop(VoltcraftBlocks.DEEPSLATE_ORE_GALENA, oreDrops(VoltcraftBlocks.DEEPSLATE_ORE_GALENA, VoltcraftItems.RAW_GALENA));
        addDrop(VoltcraftBlocks.ORE_SPHALERITE, oreDrops(VoltcraftBlocks.ORE_SPHALERITE, VoltcraftItems.RAW_SPHALERITE));
        addDrop(VoltcraftBlocks.DEEPSLATE_ORE_SPHALERITE, oreDrops(VoltcraftBlocks.DEEPSLATE_ORE_SPHALERITE, VoltcraftItems.RAW_SPHALERITE));
        addDrop(VoltcraftBlocks.ORE_SPODUMENE, oreDrops(VoltcraftBlocks.ORE_SPODUMENE, VoltcraftItems.RAW_SPODUMENE));
        addDrop(VoltcraftBlocks.DEEPSLATE_ORE_SPODUMENE, oreDrops(VoltcraftBlocks.DEEPSLATE_ORE_SPODUMENE, VoltcraftItems.RAW_SPODUMENE));
        addDrop(VoltcraftBlocks.ORE_PENTLANDITE, oreDrops(VoltcraftBlocks.ORE_PENTLANDITE, VoltcraftItems.RAW_PENTLANDITE));
        addDrop(VoltcraftBlocks.DEEPSLATE_ORE_PENTLANDITE, oreDrops(VoltcraftBlocks.DEEPSLATE_ORE_PENTLANDITE, VoltcraftItems.RAW_PENTLANDITE));
        addDrop(VoltcraftBlocks.ORE_HIGH_PURITY_QUARTZ, oreDrops(VoltcraftBlocks.ORE_HIGH_PURITY_QUARTZ, VoltcraftItems.PURE_SILICA_DUST));
        addDrop(VoltcraftBlocks.DEEPSLATE_ORE_QUARTZ, oreDrops(VoltcraftBlocks.DEEPSLATE_ORE_QUARTZ, VoltcraftItems.PURE_SILICA_DUST));

        // Cables drop themselves
        addDrop(VoltcraftBlocks.CABLE_COPPER_BARE);
        addDrop(VoltcraftBlocks.CABLE_COPPER_INSULATED);
        addDrop(VoltcraftBlocks.CABLE_COPPER_HEAVY);
        addDrop(VoltcraftBlocks.CABLE_ALUMINUM_TRANSMISSION);
        addDrop(VoltcraftBlocks.CABLE_SILVER_PRECISION);
        addDrop(VoltcraftBlocks.CABLE_GOLD_BUS);
        addDrop(VoltcraftBlocks.CABLE_STEEL_FENCE);
        addDrop(VoltcraftBlocks.CABLE_NICHROME_HEATING);
        addDrop(VoltcraftBlocks.CONDUIT_SUPERCONDUCTOR);

        // Switchgear drop themselves
        addDrop(VoltcraftBlocks.COPPER_BUSBAR);
        addDrop(VoltcraftBlocks.JUNCTION_BOX);
        addDrop(VoltcraftBlocks.KNIFE_SWITCH);
        addDrop(VoltcraftBlocks.CIRCUIT_BREAKER);
        addDrop(VoltcraftBlocks.FUSE_BOX);
        addDrop(VoltcraftBlocks.CONTACTOR_RELAY);

        // Stationary BESS blocks drop themselves
        addDrop(VoltcraftBlocks.BATTERY_BLOCK_LIFEPO4);
        addDrop(VoltcraftBlocks.BATTERY_BLOCK_LEAD_ACID);
        addDrop(VoltcraftBlocks.BATTERY_BLOCK_LTO);
        addDrop(VoltcraftBlocks.BATTERY_RACK_MODULAR);
        addDrop(VoltcraftBlocks.BATTERY_BLOCK_NIMH);
        addDrop(VoltcraftBlocks.BATTERY_BLOCK_NICD);

        // Power Conversion blocks drop themselves
        addDrop(VoltcraftBlocks.CONVERTER_DC_BUCK);
        addDrop(VoltcraftBlocks.CONVERTER_DC_BOOST);
        addDrop(VoltcraftBlocks.CONVERTER_DC_BUCK_BOOST);
        addDrop(VoltcraftBlocks.REGULATOR_LINEAR_LDO);

        addDrop(VoltcraftBlocks.TRANSFORMER_AC_STEP_DOWN);
        addDrop(VoltcraftBlocks.TRANSFORMER_AC_STEP_UP);

        addDrop(VoltcraftBlocks.RECTIFIER_BRIDGE);
        addDrop(VoltcraftBlocks.RECTIFIER_ACTIVE_SYNCHRONOUS);

        addDrop(VoltcraftBlocks.INVERTER_SQUARE_WAVE);
        addDrop(VoltcraftBlocks.INVERTER_MODIFIED_SINE);
        addDrop(VoltcraftBlocks.INVERTER_PURE_SINE);
        addDrop(VoltcraftBlocks.INVERTER_GRID_TIE);
        addDrop(VoltcraftBlocks.INVERTER_HYBRID_ESS);

        addDrop(VoltcraftBlocks.CONVERTER_EU);

        // Power Generation & Renewable Systems drop themselves
        addDrop(VoltcraftBlocks.SOLAR_PANEL_MONOCRYSTALLINE);
        addDrop(VoltcraftBlocks.SOLAR_PANEL_POLYCRYSTALLINE);
        addDrop(VoltcraftBlocks.SOLAR_PANEL_THIN_FILM);
        addDrop(VoltcraftBlocks.SOLAR_PANEL_CONCENTRATOR);
        addDrop(VoltcraftBlocks.CHARGE_CONTROLLER_MPPT);
        addDrop(VoltcraftBlocks.GENERATOR_HAND_CRANK);
        addDrop(VoltcraftBlocks.GENERATOR_PORTABLE_INVERTER);

        // Creative Testing blocks drop themselves
        addDrop(VoltcraftBlocks.CREATIVE_GENERATOR);
        addDrop(VoltcraftBlocks.CREATIVE_LOAD);
    }
}
