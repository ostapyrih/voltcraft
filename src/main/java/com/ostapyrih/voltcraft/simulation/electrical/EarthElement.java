package com.ostapyrih.voltcraft.simulation.electrical;

import com.ostapyrih.voltcraft.api.electrical.Complex;
import com.ostapyrih.voltcraft.api.electrical.ElectricalElement;
import com.mojang.serialization.Codec;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import java.util.List;

public final class EarthElement implements ElectricalElement {
        public static final double G_EARTH_S = 1000.0;

        public static final String KEY_STATE_ARRAY = "stateArray";
    public static final int[][] TERMINAL_OFFSETS = {{0, -1, 0}};

    @Override
    public int terminalCount() {
        return 1;
    }

    @Override
    public int stateCount() {
        return 0;
    }

    @Override
    public void stamp(Complex[][] y, Complex[] in, int[] terminals, Complex[] v,
                      double[] state, double omega) {
        int t = terminals[0];
        y[t][t] = y[t][t].add(new Complex(G_EARTH_S, 0.0));
    }

    @Override
    public void derivatives(double[] dxdt, double[] state, Complex[] vt, Complex[] it) {
        // No states: no-op.
    }

    public static void checkState(double[] state) {
        if (state == null || state.length != 0) {
            throw new IllegalArgumentException(
                "EarthBlockEntity holds 0 states, got length " + (state == null ? "null" : state.length));
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
