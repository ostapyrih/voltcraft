package com.ostapyrih.voltcraft.simulation.grid;

import com.ostapyrih.voltcraft.simulation.electrical.ConverterElement;
import com.ostapyrih.voltcraft.simulation.electrical.BatteryElement;
import com.ostapyrih.voltcraft.simulation.electrical.CreativeLoadElement;
import com.ostapyrih.voltcraft.simulation.electrical.CreativeGeneratorElement;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Frozen terminal-layout convention tests.
 *
 * <p>Pure Java + adapter statics, no server, no registries. The same hard
 * environment constraint as the other adapter suites applies:
 * {@code BlockEntity.&lt;clinit&gt;} touches {@code Registries}, so no test may
 * load/initialize an outer {@code BlockEntity} subclass. Only the static nested
 * element classes ({@code BatteryElement}, {@code ConverterElement}) plus the
 * registry-free {@code BlockPos}/{@code Direction} value types are touched here;
 * the outer glue ({@code getTerminalPositions} one-line delegates wiring the pure
 * {@code resolveConverterTerminals} helper to the cached {@code FACING}) is the
 * documented coverage boundary.</p>
 *
 * <p>Convention under test: 2-terminal blocks expose {@code [north, south]};
 * 4-terminal converters expose FACING-relative
 * {@code [BACK, LEFT, FRONT, RIGHT]} (input pair {@code [0..1]}, output pair
 * {@code [2..3]}, {@code +} before {@code −} within each pair).</p>
 */
class TerminalLayoutTest {

    private static BlockPos pos(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    private static int manhattan(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY())
            + Math.abs(a.getZ() - b.getZ());
    }

    /** A cable at {@code c} touches terminal {@code t} when coincident or 6-adjacent. */
    private static boolean touches(BlockPos c, BlockPos t) {
        return c.equals(t) || manhattan(c, t) == 1;
    }

    private static boolean touchesAny(BlockPos c, BlockPos[] terminals) {
        for (BlockPos t : terminals) {
            if (touches(c, t)) {
                return true;
            }
        }
        return false;
    }

    /** The 6 face-adjacent cable candidate cells around a block position. */
    private static BlockPos[] faceNeighbors(BlockPos p) {
        return new BlockPos[]{
            pos(p.getX() + 1, p.getY(), p.getZ()), pos(p.getX() - 1, p.getY(), p.getZ()),
            pos(p.getX(), p.getY() + 1, p.getZ()), pos(p.getX(), p.getY() - 1, p.getZ()),
            pos(p.getX(), p.getY(), p.getZ() + 1), pos(p.getX(), p.getY(), p.getZ() - 1)
        };
    }

    @Test
    void batteryTerminalPositionsAreOppositeHorizontalFaces() {
        assertArrayEquals(new int[]{0, 0, -1}, BatteryElement.TERMINAL_OFFSETS[0]);
        assertArrayEquals(new int[]{0, 0, 1}, BatteryElement.TERMINAL_OFFSETS[1]);
        BlockPos p = pos(10, 64, -3);
        int[] plus = BatteryElement.TERMINAL_OFFSETS[0];
        int[] minus = BatteryElement.TERMINAL_OFFSETS[1];
        BlockPos t0 = p.add(plus[0], plus[1], plus[2]);
        BlockPos t1 = p.add(minus[0], minus[1], minus[2]);
        assertEquals(p.north(), t0);
        assertEquals(p.south(), t1);
        assertEquals(1, manhattan(p, t0));
        assertEquals(1, manhattan(p, t1));
        assertEquals(2, manhattan(t0, t1));
    }

    @Test
    void converterTerminalPositionsAreTwoAdjacentPairs() {
        BlockPos p = pos(10, 64, -3);
        BlockPos[] t = ConverterElement.resolveConverterTerminals(p, Direction.NORTH);
        assertEquals(4, t.length);
        // FACING NORTH: [BACK, LEFT, FRONT, RIGHT] = [south, west, north, east].
        assertEquals(p.south(), t[0]);
        assertEquals(p.west(), t[1]);
        assertEquals(p.north(), t[2]);
        assertEquals(p.east(), t[3]);
    }

    @Test
    void converterFacingSouthMirrorsLayout() {
        BlockPos p = pos(10, 64, -3);
        BlockPos[] t = ConverterElement.resolveConverterTerminals(p, Direction.SOUTH);
        assertEquals(4, t.length);
        // FACING SOUTH: [BACK, LEFT, FRONT, RIGHT] = [north, east, south, west].
        assertEquals(p.north(), t[0]);
        assertEquals(p.east(), t[1]);
        assertEquals(p.south(), t[2]);
        assertEquals(p.west(), t[3]);
    }

    @Test
    void noSingleCableTouchesBothInputAndOutput() {
        BlockPos p = pos(10, 64, -3);
        BlockPos[] t = ConverterElement.resolveConverterTerminals(p, Direction.NORTH);
        BlockPos[] in = {t[0], t[1]};
        BlockPos[] out = {t[2], t[3]};
        // Input set {S, W} vs output set {N, E}: disjoint by construction.
        for (BlockPos a : in) {
            for (BlockPos b : out) {
                assertFalse(a.equals(b), "input and output terminals must not coincide: " + a);
            }
        }
        // No face-adjacent cable cell is coincident-with/adjacent-to members of both sets.
        for (BlockPos c : faceNeighbors(p)) {
            assertFalse(touchesAny(c, in) && touchesAny(c, out),
                "no single cable at " + c + " may bridge input and output");
        }
    }

    @Test
    void inputTerminalsAreAdjacent() {
        BlockPos p = pos(10, 64, -3);
        BlockPos[] t = ConverterElement.resolveConverterTerminals(p, Direction.NORTH);
        BlockPos s = t[0];
        BlockPos w = t[1];
        // Geometric fact: S=(0,0,1) and W=(-1,0,0) relative are diagonal
        // (|d| = 2, sharing only a vertical edge/corner), not face-adjacent.
        assertEquals(2, manhattan(s, w));
        // Hence no single face-adjacent cable cell touches both input terminals.
        for (BlockPos c : faceNeighbors(p)) {
            assertFalse(touches(c, s) && touches(c, w),
                "no single cable at " + c + " may bridge both input terminals");
        }
    }

    @Test
    void batteryTerminalsRotateWithFacing() {
        BlockPos p = pos(10, 64, -3);

        // NORTH facing: FRONT (-) = north, BACK (+) = south
        BlockPos[] north = BatteryElement.resolveTerminals(p, Direction.NORTH);
        assertEquals(p.north(), north[0]);
        assertEquals(p.south(), north[1]);

        // SOUTH facing: FRONT (-) = south, BACK (+) = north
        BlockPos[] south = BatteryElement.resolveTerminals(p, Direction.SOUTH);
        assertEquals(p.south(), south[0]);
        assertEquals(p.north(), south[1]);

        // EAST facing: FRONT (-) = east, BACK (+) = west
        BlockPos[] east = BatteryElement.resolveTerminals(p, Direction.EAST);
        assertEquals(p.east(), east[0]);
        assertEquals(p.west(), east[1]);

        // WEST facing: FRONT (-) = west, BACK (+) = east
        BlockPos[] west = BatteryElement.resolveTerminals(p, Direction.WEST);
        assertEquals(p.west(), west[0]);
        assertEquals(p.east(), west[1]);
    }

    @Test
    void allTwoTerminalBlocksShareConsistentLayout() {
        BlockPos p = pos(5, 64, 5);
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos[] batt = BatteryElement.resolveTerminals(p, dir);
            BlockPos[] load = CreativeLoadElement.resolveTerminals(p, dir);
            BlockPos[] gen = CreativeGeneratorElement.resolveTerminals(p, dir);

            assertArrayEquals(batt, load, "CreativeLoad must match Battery terminal layout for facing " + dir);
            assertArrayEquals(batt, gen, "CreativeGenerator must match Battery terminal layout for facing " + dir);
        }
    }
}
