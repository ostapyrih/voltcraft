package com.ostapyrih.voltcraft.simulation.electrical;

import com.ostapyrih.voltcraft.api.electrical.Complex;
import com.ostapyrih.voltcraft.api.electrical.ElectricalElement;
import com.ostapyrih.voltcraft.api.electrical.Stamps;
import com.mojang.serialization.Codec;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

public final class ContactorElement implements ElectricalElement {
        public static final double R_CLOSED_OHM = 0.001;

        public static final String KEY_STATE_ARRAY = "stateArray";
        public static final String KEY_CLOSED = "closed";
    public static final int[][] TERMINAL_OFFSETS = {{0, 0, -1}, {0, 0, 1}};
    public static final boolean DEFAULT_CLOSED = false;

    private final BooleanSupplier closed;

    public ContactorElement(BooleanSupplier closed) {
        this.closed = Objects.requireNonNull(closed, "closed");
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
        if (closed.getAsBoolean()) {
            Stamps.admittance(y, terminals[0], terminals[1],
                new Complex(1.0 / R_CLOSED_OHM, 0.0));
        }
    }

    @Override
    public void derivatives(double[] dxdt, double[] state, Complex[] vt, Complex[] it) {
        // No states: no-op.
    }

    public static void checkState(double[] state) {
        if (state == null || state.length != 0) {
            throw new IllegalArgumentException(
                "ContactorRelayBlockEntity holds 0 states, got length " + (state == null ? "null" : state.length));
        }
    }

    public static double[] snapshotState() {
        return new double[0];
    }

    public static void writeNbt(WriteView view, boolean closed) {
        view.put(KEY_STATE_ARRAY, Codec.DOUBLE.listOf(), List.of());
        view.putBoolean(KEY_CLOSED, closed);
    }

    public static boolean readNbtClosed(ReadView view) {
        view.read(KEY_STATE_ARRAY, Codec.DOUBLE.listOf()).orElse(List.of());
        return view.getBoolean(KEY_CLOSED, DEFAULT_CLOSED);
    }
}
