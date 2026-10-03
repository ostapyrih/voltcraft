package com.ostapyrih.voltcraft.client;

import com.ostapyrih.voltcraft.Voltcraft;
import com.ostapyrih.voltcraft.block.VoltcraftBlocks;
import com.ostapyrih.voltcraft.block.conversion.AbstractPowerConverterBlock;
import com.ostapyrih.voltcraft.block.conversion.EuConverterBlock;
import com.ostapyrih.voltcraft.block.creative.CreativeGeneratorBlock;
import com.ostapyrih.voltcraft.block.creative.CreativeLoadBlock;
import com.ostapyrih.voltcraft.block.generation.HandCrankGeneratorBlock;
import com.ostapyrih.voltcraft.block.generation.PortableGeneratorBlock;
import com.ostapyrih.voltcraft.block.generation.SolarPanelBlock;
import com.ostapyrih.voltcraft.block.storage.BatteryBlock;
import com.ostapyrih.voltcraft.block.storage.BatteryRackBlock;
import com.ostapyrih.voltcraft.item.VoltcraftItems;
import net.fabricmc.fabric.api.client.datagen.v1.provider.FabricModelProvider;
import net.fabricmc.fabric.api.datagen.v1.FabricDataOutput;
import net.minecraft.client.data.BlockStateModelGenerator;
import net.minecraft.client.data.BlockStateVariantMap;
import net.minecraft.client.data.ItemModelGenerator;
import net.minecraft.client.data.ItemModels;
import net.minecraft.client.data.Model;
import net.minecraft.client.data.Models;
import net.minecraft.client.data.TextureKey;
import net.minecraft.client.data.TextureMap;
import net.minecraft.client.data.VariantsBlockModelDefinitionCreator;
import net.minecraft.client.render.model.json.ModelVariantOperator;
import net.minecraft.client.render.model.json.WeightedVariant;
import net.minecraft.item.BlockItem;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;

import java.util.Optional;

/**
 * Automatically generates blockstates, block models, and item models
 * that reference voltcraft:block/* and voltcraft:item/* textures.
 */
public class VoltcraftModelGenerator extends FabricModelProvider {

    public static final BlockStateVariantMap<ModelVariantOperator> HORIZONTAL_ROTATION_OPERATIONS = BlockStateVariantMap
        .operations(Properties.HORIZONTAL_FACING)
        .register(Direction.NORTH, BlockStateModelGenerator.NO_OP)
        .register(Direction.EAST, BlockStateModelGenerator.ROTATE_Y_90)
        .register(Direction.SOUTH, BlockStateModelGenerator.ROTATE_Y_180)
        .register(Direction.WEST, BlockStateModelGenerator.ROTATE_Y_270);

    public static final Model DIRECTIONAL_CONVERTER_MODEL = new Model(
        Optional.of(Identifier.of("minecraft", "block/cube")),
        Optional.empty(),
        TextureKey.NORTH,
        TextureKey.SOUTH,
        TextureKey.EAST,
        TextureKey.WEST,
        TextureKey.UP,
        TextureKey.DOWN,
        TextureKey.PARTICLE
    );

    public static final Model SOLAR_SLAB_MODEL = new Model(
        Optional.of(Identifier.of("minecraft", "block/slab")),
        Optional.empty(),
        TextureKey.BOTTOM,
        TextureKey.TOP,
        TextureKey.SIDE
    );

    public VoltcraftModelGenerator(FabricDataOutput output) {
        super(output);
    }

    @Override
    public void generateBlockStateModels(BlockStateModelGenerator blockStateModelGenerator) {
        VoltcraftBlocks.ALL_BLOCKS.values().forEach(block -> {
            String name = Registries.BLOCK.getId(block).getPath();

            if (block instanceof AbstractPowerConverterBlock || block instanceof EuConverterBlock) {
                // 4-Terminal Directional Power Converter:
                // Front / North = Output Port 1 (+ / L)
                // Back / South = Input Port 1 (+ / L)
                // West = Left = Input Port 2 (- / N)
                // East = Right = Output Port 2 (- / N)
                // Up = Top (Top-down wiring diagram & vents)
                // Down = Bottom (Base plate)
                TextureMap textureMap = new TextureMap()
                    .put(TextureKey.NORTH, Identifier.of(Voltcraft.MOD_ID, "block/" + name + "_front"))
                    .put(TextureKey.SOUTH, Identifier.of(Voltcraft.MOD_ID, "block/" + name + "_back"))
                    .put(TextureKey.EAST, Identifier.of(Voltcraft.MOD_ID, "block/" + name + "_right"))
                    .put(TextureKey.WEST, Identifier.of(Voltcraft.MOD_ID, "block/" + name + "_left"))
                    .put(TextureKey.UP, Identifier.of(Voltcraft.MOD_ID, "block/" + name + "_top"))
                    .put(TextureKey.DOWN, Identifier.of(Voltcraft.MOD_ID, "block/" + name + "_bottom"))
                    .put(TextureKey.PARTICLE, Identifier.of(Voltcraft.MOD_ID, "block/" + name + "_front"));

                Identifier modelId = DIRECTIONAL_CONVERTER_MODEL.upload(block, textureMap, blockStateModelGenerator.modelCollector);
                WeightedVariant variant = BlockStateModelGenerator.createWeightedVariant(modelId);

                blockStateModelGenerator.blockStateCollector.accept(
                    VariantsBlockModelDefinitionCreator.of(block, variant).apply(HORIZONTAL_ROTATION_OPERATIONS)
                );
                blockStateModelGenerator.itemModelOutput.accept(block.asItem(), ItemModels.basic(modelId));
            } else if (block instanceof HandCrankGeneratorBlock || block instanceof PortableGeneratorBlock) {
                // 2-Terminal Directional Generator:
                // Front / North & Back / South = Terminals
                // East / West = Side
                TextureMap textureMap = new TextureMap()
                    .put(TextureKey.NORTH, Identifier.of(Voltcraft.MOD_ID, "block/" + name + "_front"))
                    .put(TextureKey.SOUTH, Identifier.of(Voltcraft.MOD_ID, "block/" + name + "_back"))
                    .put(TextureKey.EAST, Identifier.of(Voltcraft.MOD_ID, "block/" + name + "_side"))
                    .put(TextureKey.WEST, Identifier.of(Voltcraft.MOD_ID, "block/" + name + "_side"))
                    .put(TextureKey.UP, Identifier.of(Voltcraft.MOD_ID, "block/" + name + "_top"))
                    .put(TextureKey.DOWN, Identifier.of(Voltcraft.MOD_ID, "block/" + name + "_bottom"))
                    .put(TextureKey.PARTICLE, Identifier.of(Voltcraft.MOD_ID, "block/" + name + "_front"));

                Identifier modelId = DIRECTIONAL_CONVERTER_MODEL.upload(block, textureMap, blockStateModelGenerator.modelCollector);
                WeightedVariant variant = BlockStateModelGenerator.createWeightedVariant(modelId);

                blockStateModelGenerator.blockStateCollector.accept(
                    VariantsBlockModelDefinitionCreator.of(block, variant).apply(HORIZONTAL_ROTATION_OPERATIONS)
                );
                blockStateModelGenerator.itemModelOutput.accept(block.asItem(), ItemModels.basic(modelId));
            } else if (block instanceof SolarPanelBlock) {
                // Slab-style Photovoltaic Panel
                TextureMap textureMap = new TextureMap()
                    .put(TextureKey.TOP, Identifier.of(Voltcraft.MOD_ID, "block/" + name + "_top"))
                    .put(TextureKey.BOTTOM, Identifier.of(Voltcraft.MOD_ID, "block/" + name + "_bottom"))
                    .put(TextureKey.SIDE, Identifier.of(Voltcraft.MOD_ID, "block/" + name + "_side"))
                    .put(TextureKey.PARTICLE, Identifier.of(Voltcraft.MOD_ID, "block/" + name + "_top"));

                Identifier modelId = SOLAR_SLAB_MODEL.upload(block, textureMap, blockStateModelGenerator.modelCollector);
                WeightedVariant variant = BlockStateModelGenerator.createWeightedVariant(modelId);

                blockStateModelGenerator.blockStateCollector.accept(
                    VariantsBlockModelDefinitionCreator.of(block, variant).apply(HORIZONTAL_ROTATION_OPERATIONS)
                );
                blockStateModelGenerator.itemModelOutput.accept(block.asItem(), ItemModels.basic(modelId));
            } else if (block instanceof BatteryBlock || block instanceof BatteryRackBlock ||
                       block instanceof CreativeGeneratorBlock || block instanceof CreativeLoadBlock) {
                // Blocks with distinct top (terminals/readouts), bottom, and sides
                TextureMap textureMap = new TextureMap()
                    .put(TextureKey.TOP, Identifier.of(Voltcraft.MOD_ID, "block/" + name + "_top"))
                    .put(TextureKey.BOTTOM, Identifier.of(Voltcraft.MOD_ID, "block/" + name + "_bottom"))
                    .put(TextureKey.SIDE, Identifier.of(Voltcraft.MOD_ID, "block/" + name + "_side"))
                    .put(TextureKey.PARTICLE, Identifier.of(Voltcraft.MOD_ID, "block/" + name + "_side"));

                Identifier modelId = Models.CUBE_BOTTOM_TOP.upload(block, textureMap, blockStateModelGenerator.modelCollector);
                WeightedVariant variant = BlockStateModelGenerator.createWeightedVariant(modelId);

                blockStateModelGenerator.blockStateCollector.accept(
                    VariantsBlockModelDefinitionCreator.of(block, variant).apply(HORIZONTAL_ROTATION_OPERATIONS)
                );
                blockStateModelGenerator.itemModelOutput.accept(block.asItem(), ItemModels.basic(modelId));
            } else {
                // Default cube_all for cables, switchgear, ores, and simple cubes
                blockStateModelGenerator.registerSimpleCubeAll(block);
            }
        });
    }

    @Override
    public void generateItemModels(ItemModelGenerator itemModelGenerator) {
        VoltcraftItems.ALL_ITEMS.values().forEach(item -> {
            if (!(item instanceof BlockItem)) {
                itemModelGenerator.register(item, Models.GENERATED);
            }
        });
    }
}
