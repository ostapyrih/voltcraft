package com.ostapyrih.voltcraft.block.entity;

import com.ostapyrih.voltcraft.Voltcraft;
import com.ostapyrih.voltcraft.block.VoltcraftBlocks;
import com.ostapyrih.voltcraft.block.entity.conversion.ConverterBlockEntity;
import com.ostapyrih.voltcraft.block.entity.conversion.EuConverterBlockEntity;
import com.ostapyrih.voltcraft.block.entity.conversion.InverterBlockEntity;
import com.ostapyrih.voltcraft.block.entity.conversion.RectifierBlockEntity;
import com.ostapyrih.voltcraft.block.entity.conversion.TransformerBlockEntity;
import com.ostapyrih.voltcraft.block.entity.creative.CreativeGeneratorBlockEntity;
import com.ostapyrih.voltcraft.block.entity.creative.CreativeLoadBlockEntity;
import com.ostapyrih.voltcraft.block.entity.generation.ChargeControllerBlockEntity;
import com.ostapyrih.voltcraft.block.entity.generation.HandCrankGeneratorBlockEntity;
import com.ostapyrih.voltcraft.block.entity.generation.PortableGeneratorBlockEntity;
import com.ostapyrih.voltcraft.block.entity.generation.SolarPanelBlockEntity;
import com.ostapyrih.voltcraft.block.entity.storage.BatteryBlockEntity;
import com.ostapyrih.voltcraft.block.entity.storage.BatteryRackBlockEntity;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

/**
 * Registry for VoltCraft BlockEntity types.
 */
public class VoltcraftBlockEntityTypes {

    public static final BlockEntityType<BatteryBlockEntity> BATTERY_BLOCK_ENTITY = Registry.register(
        Registries.BLOCK_ENTITY_TYPE,
        Identifier.of(Voltcraft.MOD_ID, "battery_block_entity"),
        FabricBlockEntityTypeBuilder.create(
            BatteryBlockEntity::new,
            VoltcraftBlocks.BATTERY_BLOCK_LIFEPO4,
            VoltcraftBlocks.BATTERY_BLOCK_LEAD_ACID,
            VoltcraftBlocks.BATTERY_BLOCK_LTO,
            VoltcraftBlocks.BATTERY_BLOCK_NIMH,
            VoltcraftBlocks.BATTERY_BLOCK_NICD
        ).build()
    );

    public static final BlockEntityType<BatteryRackBlockEntity> BATTERY_RACK_BLOCK_ENTITY = Registry.register(
        Registries.BLOCK_ENTITY_TYPE,
        Identifier.of(Voltcraft.MOD_ID, "battery_rack_block_entity"),
        FabricBlockEntityTypeBuilder.create(
            BatteryRackBlockEntity::new,
            VoltcraftBlocks.BATTERY_RACK_MODULAR
        ).build()
    );

    public static final BlockEntityType<ConverterBlockEntity> CONVERTER_BLOCK_ENTITY = Registry.register(
        Registries.BLOCK_ENTITY_TYPE,
        Identifier.of(Voltcraft.MOD_ID, "converter_block_entity"),
        FabricBlockEntityTypeBuilder.create(
            ConverterBlockEntity::new,
            VoltcraftBlocks.CONVERTER_DC_BUCK,
            VoltcraftBlocks.CONVERTER_DC_BOOST,
            VoltcraftBlocks.CONVERTER_DC_BUCK_BOOST,
            VoltcraftBlocks.REGULATOR_LINEAR_LDO
        ).build()
    );

    public static final BlockEntityType<TransformerBlockEntity> TRANSFORMER_BLOCK_ENTITY = Registry.register(
        Registries.BLOCK_ENTITY_TYPE,
        Identifier.of(Voltcraft.MOD_ID, "transformer_block_entity"),
        FabricBlockEntityTypeBuilder.create(
            TransformerBlockEntity::new,
            VoltcraftBlocks.TRANSFORMER_AC_STEP_DOWN,
            VoltcraftBlocks.TRANSFORMER_AC_STEP_UP
        ).build()
    );

    public static final BlockEntityType<RectifierBlockEntity> RECTIFIER_BLOCK_ENTITY = Registry.register(
        Registries.BLOCK_ENTITY_TYPE,
        Identifier.of(Voltcraft.MOD_ID, "rectifier_block_entity"),
        FabricBlockEntityTypeBuilder.create(
            RectifierBlockEntity::new,
            VoltcraftBlocks.RECTIFIER_BRIDGE,
            VoltcraftBlocks.RECTIFIER_ACTIVE_SYNCHRONOUS
        ).build()
    );

    public static final BlockEntityType<InverterBlockEntity> INVERTER_BLOCK_ENTITY = Registry.register(
        Registries.BLOCK_ENTITY_TYPE,
        Identifier.of(Voltcraft.MOD_ID, "inverter_block_entity"),
        FabricBlockEntityTypeBuilder.create(
            InverterBlockEntity::new,
            VoltcraftBlocks.INVERTER_SQUARE_WAVE,
            VoltcraftBlocks.INVERTER_MODIFIED_SINE,
            VoltcraftBlocks.INVERTER_PURE_SINE,
            VoltcraftBlocks.INVERTER_GRID_TIE,
            VoltcraftBlocks.INVERTER_HYBRID_ESS
        ).build()
    );

    public static final BlockEntityType<EuConverterBlockEntity> EU_CONVERTER_BLOCK_ENTITY = Registry.register(
        Registries.BLOCK_ENTITY_TYPE,
        Identifier.of(Voltcraft.MOD_ID, "eu_converter_block_entity"),
        FabricBlockEntityTypeBuilder.create(
            EuConverterBlockEntity::new,
            VoltcraftBlocks.CONVERTER_EU
        ).build()
    );

    public static final BlockEntityType<SolarPanelBlockEntity> SOLAR_PANEL_BLOCK_ENTITY = Registry.register(
        Registries.BLOCK_ENTITY_TYPE,
        Identifier.of(Voltcraft.MOD_ID, "solar_panel_block_entity"),
        FabricBlockEntityTypeBuilder.create(
            SolarPanelBlockEntity::new,
            VoltcraftBlocks.SOLAR_PANEL_MONOCRYSTALLINE,
            VoltcraftBlocks.SOLAR_PANEL_POLYCRYSTALLINE,
            VoltcraftBlocks.SOLAR_PANEL_THIN_FILM,
            VoltcraftBlocks.SOLAR_PANEL_CONCENTRATOR
        ).build()
    );

    public static final BlockEntityType<ChargeControllerBlockEntity> CHARGE_CONTROLLER_BLOCK_ENTITY = Registry.register(
        Registries.BLOCK_ENTITY_TYPE,
        Identifier.of(Voltcraft.MOD_ID, "charge_controller_block_entity"),
        FabricBlockEntityTypeBuilder.create(
            ChargeControllerBlockEntity::new,
            VoltcraftBlocks.CHARGE_CONTROLLER_MPPT
        ).build()
    );

    public static final BlockEntityType<HandCrankGeneratorBlockEntity> HAND_CRANK_GENERATOR_BLOCK_ENTITY = Registry.register(
        Registries.BLOCK_ENTITY_TYPE,
        Identifier.of(Voltcraft.MOD_ID, "hand_crank_generator_block_entity"),
        FabricBlockEntityTypeBuilder.create(
            HandCrankGeneratorBlockEntity::new,
            VoltcraftBlocks.GENERATOR_HAND_CRANK
        ).build()
    );

    public static final BlockEntityType<PortableGeneratorBlockEntity> PORTABLE_GENERATOR_BLOCK_ENTITY = Registry.register(
        Registries.BLOCK_ENTITY_TYPE,
        Identifier.of(Voltcraft.MOD_ID, "portable_generator_block_entity"),
        FabricBlockEntityTypeBuilder.create(
            PortableGeneratorBlockEntity::new,
            VoltcraftBlocks.GENERATOR_PORTABLE_INVERTER
        ).build()
    );

    public static final BlockEntityType<CreativeGeneratorBlockEntity> CREATIVE_GENERATOR_BLOCK_ENTITY = Registry.register(
        Registries.BLOCK_ENTITY_TYPE,
        Identifier.of(Voltcraft.MOD_ID, "creative_generator_block_entity"),
        FabricBlockEntityTypeBuilder.create(
            CreativeGeneratorBlockEntity::new,
            VoltcraftBlocks.CREATIVE_GENERATOR
        ).build()
    );

    public static final BlockEntityType<CreativeLoadBlockEntity> CREATIVE_LOAD_BLOCK_ENTITY = Registry.register(
        Registries.BLOCK_ENTITY_TYPE,
        Identifier.of(Voltcraft.MOD_ID, "creative_load_block_entity"),
        FabricBlockEntityTypeBuilder.create(
            CreativeLoadBlockEntity::new,
            VoltcraftBlocks.CREATIVE_LOAD
        ).build()
    );

    public static void initialize() {
        // Loads static fields
    }
}
