package com.ostapyrih.voltcraft.simulation.grid;

import com.ostapyrih.voltcraft.block.VoltcraftBlocks;
import com.ostapyrih.voltcraft.block.entity.VoltcraftBlockEntityTypes;
import com.ostapyrih.voltcraft.item.VoltcraftItemGroups;
import com.ostapyrih.voltcraft.item.VoltcraftItems;
import com.ostapyrih.voltcraft.block.switchgear.EarthRodBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldView;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Earth-rod registration tests (block + item + creative tab + BE-type wiring).
 *
 * <p>Why a separate class: {@code AdapterSwitchgearTest} documents a hard environment
 * constraint — {@code BlockEntity.&lt;clinit&gt;} touches {@code Registries}, and
 * {@code Bootstrap.initialize()} crashes in plain unit-test runtimes (vanilla registry
 * code needs Fabric access-wideners). Every assertion here touches registry-bound
 * classes, so each test aborts (not fails) when registries are not bootstrapped and
 * runs fully in-game / under the Fabric loader runtime. Nothing in
 * {@code AdapterSwitchgearTest} was altered.</p>
 *
 * <p>Unit-testable boundary that loads everywhere (static {@code EarthElement} stamp,
 * states, NBT, terminal offsets) stays covered by {@code AdapterSwitchgearTest}.</p>
 */
class EarthRodRegistrationTest {

    private static void assumeBootstrapped() {
        boolean live;
        try {
            // Any touch of a live game registry proves bootstrap; in plain unit
            // runtimes Registries static init fails (needs Fabric access-wideners).
            live = Registries.BLOCK.getIds().size() > 0;
        } catch (Throwable t) {
            live = false;
        }
        assumeTrue(live, "requires bootstrapped game registries; runs in-game/Fabric loader runtime");
    }

    private static WorldView worldReturning(BlockState ground) {
        return (WorldView) Proxy.newProxyInstance(EarthRodRegistrationTest.class.getClassLoader(),
            new Class<?>[]{WorldView.class}, (proxy, method, args) -> {
                if (method.getName().equals("getBlockState")) {
                    return ground;
                }
                throw new UnsupportedOperationException("WorldView fake: " + method.getName());
            });
    }

    @Test
    void earthRodBlockRegistered() {
        assumeBootstrapped();
        assertNotNull(VoltcraftBlocks.EARTH_ROD, "voltcraft:earth_rod block missing");
        assertNotNull(VoltcraftItems.EARTH_ROD, "voltcraft:earth_rod item missing");
        assertNotNull(VoltcraftBlockEntityTypes.EARTH_BLOCK_ENTITY, "earth BE type missing");
    }

    @Test
    void earthRodBlockEntityTypeSupportsBlockState() {
        assumeBootstrapped();
        BlockState rodState = VoltcraftBlocks.EARTH_ROD.getDefaultState();
        assertTrue(VoltcraftBlockEntityTypes.EARTH_BLOCK_ENTITY.supports(rodState),
            "EARTH_BLOCK_ENTITY must support the earth rod default state");
    }

    @Test
    void earthRodPlacesOnlyOnValidGround() {
        assumeBootstrapped();
        EarthRodBlock rod = (EarthRodBlock) VoltcraftBlocks.EARTH_ROD;
        BlockState rodState = rod.getDefaultState();
        BlockPos pos = new BlockPos(0, 64, 0);
        assertTrue(rod.canPlaceAt(rodState, worldReturning(Blocks.DIRT.getDefaultState()), pos),
            "rod must place over dirt");
        assertTrue(rod.canPlaceAt(rodState, worldReturning(Blocks.GRASS_BLOCK.getDefaultState()), pos),
            "rod must place over grass");
        assertTrue(rod.canPlaceAt(rodState, worldReturning(Blocks.STONE.getDefaultState()), pos),
            "rod must place over stone");
        assertFalse(rod.canPlaceAt(rodState, worldReturning(Blocks.AIR.getDefaultState()), pos),
            "rod must not float over air");
    }

    @Test
    void earthRodItemAppearsInCreativeTab() {
        assumeBootstrapped();
        boolean found = false;
        for (ItemStack stack : VoltcraftItemGroups.GRID_GROUP.getDisplayStacks()) {
            if (stack.isOf(VoltcraftItems.EARTH_ROD)) {
                found = true;
                break;
            }
        }
        assertTrue(found, "earth rod item must appear in the grid creative tab");
    }
}
