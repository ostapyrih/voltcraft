package com.ostapyrih.voltcraft.simulation.electrical;

import com.ostapyrih.voltcraft.api.electrical.Complex;
import com.ostapyrih.voltcraft.api.electrical.ElectricalElement;
import com.ostapyrih.voltcraft.api.electrical.Stamps;
import com.mojang.serialization.Codec;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import java.util.List;

public final class BusbarElement implements ElectricalElement {
        public static final double R_BUSBAR_OHM = 0.0005;

        public static final String KEY_STATE_ARRAY = "stateArray";
    public static final int[][] TERMINAL_OFFSETS = {{0, 0, -1}, {0, 0, 1}};

    /** FACING-relative: {@code [FRONT, BACK]}; null facing degrades to NORTH. */
    public static BlockPos[] resolveTerminals(BlockPos pos, Direction facing) {
        Direction f = facing != null ? facing : Direction.NORTH;
        return new BlockPos[]{pos.offset(f), pos.offset(f.getOpposite())};
    }

    @Override
    public int terminalCount() {
        return 2;
    }

    @Override
    public int stateCount() {
        return 0;
    }

    @Override
    public void stamp(Complex[][] y, Complex[] in, int[] terminals, Complex[] v,
                      double[] state, double omega) {
        Stamps.admittance(y, terminals[0], terminals[1],
            new Complex(1.0 / R_BUSBAR_OHM, 0.0));
    }

    @Override
    public void derivatives(double[] dxdt, double[] state, Complex[] vt, Complex[] it) {
        // No states: no-op.
    }

    public static void checkState(double[] state) {
        if (state == null || state.length != 0) {
            throw new IllegalArgumentException(
                "CopperBusbarBlockEntity holds 0 states, got length " + (state == null ? "null" : state.length));
        }
    }

    public static double[] snapshotState() {
        return new double[0];
    }

    public static void writeNbt(WriteView view) {
        view.put(KEY_STATE_ARRAY, Codec.DOUBLE.listOf(), List.of());
    }

    public static void readNbt(ReadView view) {
        view.read(KEY_STATE_ARRAY, Codec.DOUBLE.listOf()).orElse(List.of());
    }
}
