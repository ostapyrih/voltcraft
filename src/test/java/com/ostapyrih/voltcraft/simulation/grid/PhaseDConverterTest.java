package com.ostapyrih.voltcraft.simulation.grid;

import com.ostapyrih.voltcraft.api.electrical.Complex;
import com.ostapyrih.voltcraft.api.electrical.ElectricalElement;
import com.ostapyrih.voltcraft.api.electrical.GridConstants;
import com.ostapyrih.voltcraft.api.electrical.Stamps;
import com.ostapyrih.voltcraft.block.entity.conversion.AbstractPowerConverterBlockEntity;
import com.ostapyrih.voltcraft.block.entity.conversion.AbstractPowerConverterBlockEntity.ConverterElement;
import com.ostapyrih.voltcraft.block.entity.switchgear.EarthBlockEntity;
import com.ostapyrih.voltcraft.simulation.grid.ElectricalKernel.KernelSolveResult;
import com.ostapyrih.voltcraft.simulation.solver.ComplexNodalSolver;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase D converter tests: 4-terminal converter kernel adapters.
 *
 * <p>Pure Java + kernel + adapter logic, no server, no registries. The same hard
 * environment constraint as Phases B/C applies: {@code BlockEntity.&lt;clinit&gt;}
 * touches {@code Registries}, so no test may load/initialize an outer
 * {@code BlockEntity} subclass. Every branch of Phase D logic (4-terminal stamps,
 * derivatives telemetry, discrete staging/trip transitions, state validation, NBT
 * bodies, terminal offsets, defaults) lives in the static nested
 * {@link ConverterElement}, which initializes independently of its enclosing BE class
 * and is driven directly here with supplier-injected discrete cells. Only compile-time
 * constants and nested classes of the BE files are referenced — never anything that
 * would initialize the outer BE class. The outer glue (BE-owned staged/trip fields,
 * one-line delegates, vanilla NBT overrides, {@code tickElectrical} bodies mirroring
 * the covered statics 1:1) is the documented coverage boundary.</p>
 *
 * <p>Layout note: {@code terminals[0..1]} is the input pair (east in+, west in−),
 * {@code terminals[2..3]} is the output pair (north out+, south out−). The two pairs
 * are galvanically isolated: the stamp never writes cross-pair admittance.</p>
 */
class PhaseDConverterTest {

    // ---- local fixtures ----

    /** Mutable staged cells standing in for the BE-owned fields. */
    static final class Staged {
        final boolean[] tripped = {false};
        final double[] demand = {0.0};
        final double[] emf = {0.0};
        final double[] vnom = {48.0};
        final double[] tele = new double[ConverterElement.TELE_LEN];

        ConverterElement element() {
            return new ConverterElement(() -> tripped[0], () -> demand[0],
                () -> emf[0], () -> vnom[0], tele);
        }
    }

    static final class TestThevenin implements ElectricalElement {
        private final Complex g;
        private final Complex emf;

        TestThevenin(double voltage, double seriesResistance) {
            this.g = new Complex(1.0 / seriesResistance, 0.0);
            this.emf = new Complex(voltage, 0.0);
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
            Stamps.thevenin(y, in, terminals[0], terminals[1], g, emf);
        }

        @Override
        public void derivatives(double[] dxdt, double[] state, Complex[] vt, Complex[] it) {
        }
    }

    static final class TestResistor implements ElectricalElement {
        private final double resistance;

        TestResistor(double resistance) {
            this.resistance = resistance;
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
            Stamps.admittance(y, terminals[0], terminals[1], new Complex(1.0 / resistance, 0.0));
        }

        @Override
        public void derivatives(double[] dxdt, double[] state, Complex[] vt, Complex[] it) {
        }
    }

    // ---- Proxy NBT fakes (cover production writeNbt/readNbt bodies) ----

    private static WriteView writeFake(Map<String, Object> store) {
        return (WriteView) Proxy.newProxyInstance(PhaseDConverterTest.class.getClassLoader(),
            new Class<?>[]{WriteView.class}, (proxy, method, args) -> {
                String name = method.getName();
                if (name.equals("isEmpty")) {
                    return store.isEmpty();
                }
                if (name.equals("remove")) {
                    store.remove(args[0]);
                    return null;
                }
                if (name.startsWith("put")) {
                    store.put((String) args[0], args[args.length - 1]);
                    return null;
                }
                throw new UnsupportedOperationException("WriteView fake: " + name);
            });
    }

    private static ReadView readFake(Map<String, Object> store) {
        return (ReadView) Proxy.newProxyInstance(PhaseDConverterTest.class.getClassLoader(),
            new Class<?>[]{ReadView.class}, (proxy, method, args) -> {
                String name = method.getName();
                if (name.equals("getRegistries")) {
                    return null;
                }
                if (args != null && args.length >= 1 && args[0] instanceof String key) {
                    Object stored = store.get(key);
                    Class<?> rt = method.getReturnType();
                    if (rt == Optional.class) {
                        return Optional.ofNullable(stored);
                    }
                    if (rt == boolean.class) {
                        return stored instanceof Boolean b ? b : args[args.length - 1];
                    }
                    if (rt == double.class) {
                        return stored instanceof Number n ? n.doubleValue() : args[args.length - 1];
                    }
                    if (rt == String.class) {
                        return stored instanceof String s ? s : args[args.length - 1];
                    }
                }
                throw new UnsupportedOperationException("ReadView fake: " + name);
            });
    }

    private static double inputDifferential(Complex[] v) {
        return v[0].re - v[1].re;
    }

    private static double outputDifferential(Complex[] v) {
        return v[2].re - v[3].re;
    }

    // ---- stamp Y/I for known staged values (hand-computed) ----

    @Test
    void stampDcInputConstantPowerAndOutputThevenin() {
        Staged s = new Staged();
        s.demand[0] = 100.0;
        s.emf[0] = 12.0;
        s.vnom[0] = 48.0;
        ConverterElement el = s.element();
        assertEquals(4, el.terminalCount());
        assertEquals(0, el.stateCount());

        Complex[][] y = ComplexNodalSolver.zeroMatrix(4);
        Complex[] inj = ComplexNodalSolver.zeroVector(4);
        // Input differential v0 = 48 V; constant-power linearization: g = -P/v0^2, iEq = 2P/v0.
        Complex[] v = {new Complex(48.0, 0.0), new Complex(0.0, 0.0),
            new Complex(0.0, 0.0), new Complex(0.0, 0.0)};
        el.stamp(y, inj, new int[]{0, 1, 2, 3}, v, new double[0], 0.0);

        double gIn = -100.0 / (48.0 * 48.0);
        double iEq = 2.0 * 100.0 / 48.0;
        assertEquals(gIn, y[0][0].re, 1e-12);
        assertEquals(gIn, y[1][1].re, 1e-12);
        assertEquals(-gIn, y[0][1].re, 1e-12);
        assertEquals(-gIn, y[1][0].re, 1e-12);
        assertEquals(-iEq, inj[0].re, 1e-12);
        assertEquals(iEq, inj[1].re, 1e-12);

        // Output Thevenin: g = 1/0.05 = 20 S, Norton current g*emf = 240 A into node 2.
        assertEquals(20.0, y[2][2].re, 1e-12);
        assertEquals(20.0, y[3][3].re, 1e-12);
        assertEquals(-20.0, y[2][3].re, 1e-12);
        assertEquals(-20.0, y[3][2].re, 1e-12);
        assertEquals(240.0, inj[2].re, 1e-9);
        assertEquals(-240.0, inj[3].re, 1e-9);

        // No cross-coupling between the isolated pairs; all imaginary parts zero.
        assertEquals(0.0, y[0][2].re, 0.0);
        assertEquals(0.0, y[0][3].re, 0.0);
        assertEquals(0.0, y[1][2].re, 0.0);
        assertEquals(0.0, y[1][3].re, 0.0);
        assertEquals(0.0, y[2][0].re, 0.0);
        assertEquals(0.0, y[3][1].re, 0.0);
        for (Complex[] row : y) {
            for (Complex c : row) {
                assertEquals(0.0, c.im, 0.0);
            }
        }
        for (Complex c : inj) {
            assertEquals(0.0, c.im, 0.0);
        }
    }

    @Test
    void stampAcInputResistiveFallback() {
        Staged s = new Staged();
        s.demand[0] = 100.0;
        s.emf[0] = 12.0;
        s.vnom[0] = 48.0;
        ConverterElement el = s.element();

        Complex[][] y = ComplexNodalSolver.zeroMatrix(4);
        Complex[] inj = ComplexNodalSolver.zeroVector(4);
        Complex[] v = ComplexNodalSolver.zeroVector(4);
        el.stamp(y, inj, new int[]{0, 1, 2, 3}, v, new double[0],
            GridConstants.AC_OMEGA_RAD_PER_S);

        // AC approximation: R = Vnom^2 / P = 23.04 ohm, no parallel current source.
        double g = 100.0 / (48.0 * 48.0);
        assertEquals(g, y[0][0].re, 1e-12);
        assertEquals(g, y[1][1].re, 1e-12);
        assertEquals(-g, y[0][1].re, 1e-12);
        assertEquals(-g, y[1][0].re, 1e-12);
        assertEquals(0.0, inj[0].re, 0.0);
        assertEquals(0.0, inj[1].re, 0.0);

        // Output Thevenin is phasor-safe and identical to the DC case.
        assertEquals(20.0, y[2][2].re, 1e-12);
        assertEquals(240.0, inj[2].re, 1e-9);
        assertEquals(-240.0, inj[3].re, 1e-9);
    }

    @Test
    void trippedOpensBothSides() {
        Staged s = new Staged();
        s.tripped[0] = true;
        s.demand[0] = 100.0;
        s.emf[0] = 12.0;
        ConverterElement el = s.element();

        Complex[][] y = ComplexNodalSolver.zeroMatrix(4);
        Complex[] inj = ComplexNodalSolver.zeroVector(4);
        Complex[] v = {new Complex(48.0, 0.0), new Complex(0.0, 0.0),
            new Complex(12.0, 0.0), new Complex(0.0, 0.0)};
        el.stamp(y, inj, new int[]{0, 1, 2, 3}, v, new double[0], 0.0);

        for (Complex[] row : y) {
            for (Complex c : row) {
                assertEquals(Complex.ZERO, c);
            }
        }
        for (Complex c : inj) {
            assertEquals(Complex.ZERO, c);
        }
    }

    @Test
    void zeroDemandOpensInputOnlyAndZeroEmfOpensOutputOnly() {
        Staged s = new Staged();
        s.demand[0] = 0.0;
        s.emf[0] = 12.0;
        ConverterElement el = s.element();

        Complex[][] y = ComplexNodalSolver.zeroMatrix(4);
        Complex[] inj = ComplexNodalSolver.zeroVector(4);
        Complex[] v = {new Complex(48.0, 0.0), new Complex(0.0, 0.0),
            new Complex(0.0, 0.0), new Complex(0.0, 0.0)};
        el.stamp(y, inj, new int[]{0, 1, 2, 3}, v, new double[0], 0.0);
        assertEquals(0.0, y[0][0].re, 0.0);
        assertEquals(0.0, y[1][1].re, 0.0);
        assertEquals(0.0, inj[0].re, 0.0);
        assertEquals(0.0, inj[1].re, 0.0);
        assertEquals(20.0, y[2][2].re, 1e-12);
        assertEquals(240.0, inj[2].re, 1e-9);

        s.demand[0] = 100.0;
        s.emf[0] = 0.0;
        Complex[][] y2 = ComplexNodalSolver.zeroMatrix(4);
        Complex[] inj2 = ComplexNodalSolver.zeroVector(4);
        el.stamp(y2, inj2, new int[]{0, 1, 2, 3}, v, new double[0], 0.0);
        assertEquals(-100.0 / (48.0 * 48.0), y2[0][0].re, 1e-12);
        assertEquals(0.0, y2[2][2].re, 0.0);
        assertEquals(0.0, inj2[2].re, 0.0);
        assertEquals(0.0, inj2[3].re, 0.0);
    }

    // ---- derivatives telemetry (write-only) ----

    @Test
    void derivativesWriteOnlyTelemetry() {
        Staged s = new Staged();
        ConverterElement el = s.element();
        Complex[] vt = {new Complex(48.0, 0.0), new Complex(0.0, 0.0),
            new Complex(12.0, 0.0), new Complex(0.0, 0.0)};
        // Output delivering 1 A: current leaves the positive terminal, so the
        // into-element current It[2] is -1 A and powerInto is -12 W (delivered +12 W).
        Complex[] it = {new Complex(2.08, 0.0), new Complex(-2.08, 0.0),
            new Complex(-1.0, 0.0), new Complex(1.0, 0.0)};
        el.derivatives(new double[0], new double[0], vt, it);
        assertEquals(12.0, s.tele[ConverterElement.TELE_P_OUT], 1e-12);
        assertEquals(48.0, s.tele[ConverterElement.TELE_V_IN], 1e-12);
        assertEquals(12.0, s.tele[ConverterElement.TELE_V_OUT], 1e-12);
        assertEquals(1.0, s.tele[ConverterElement.TELE_I_OUT], 1e-12);

        // Null-safe: degenerate vectors zero the cache instead of throwing.
        el.derivatives(new double[0], new double[0], new Complex[0], new Complex[0]);
        assertArrayEquals(new double[4], s.tele, 0.0);
        el.derivatives(new double[0], new double[0], null, null);
        assertArrayEquals(new double[4], s.tele, 0.0);
    }

    // ---- discrete staging + trip table (pure Item-10 helpers) ----

    @Test
    void demandAndEmfStagingFromTelemetry() {
        // Demand = delivered / efficiency + idle draw.
        assertEquals(102.0, ConverterElement.stageDemandWatts(90.0, 0.9, false, 48.0), 1e-9);
        assertEquals(2.0, ConverterElement.stageDemandWatts(0.0, 0.9, false, 48.0), 1e-9);
        assertEquals(0.0, ConverterElement.stageDemandWatts(90.0, 0.9, true, 48.0), 0.0);
        assertEquals(0.0, ConverterElement.stageDemandWatts(90.0, 0.9, false, 0.5), 0.0);
        // Non-finite efficiency degrades to the floor, never NaN demand.
        assertEquals(902.0, ConverterElement.stageDemandWatts(90.0, Double.NaN, false, 48.0), 1e-9);
        assertEquals(2.0, ConverterElement.stageDemandWatts(Double.NaN, 0.9, false, 48.0), 1e-9);

        // EMF passes the subclass-computed raw target through the discrete gate.
        assertEquals(12.0, ConverterElement.stageEmf(12.0, false, 48.0, 60.0), 0.0);
        assertEquals(0.0, ConverterElement.stageEmf(12.0, true, 48.0, 60.0), 0.0);
        assertEquals(0.0, ConverterElement.stageEmf(12.0, false, 0.0, 60.0), 0.0);
        assertEquals(0.0, ConverterElement.stageEmf(12.0, false, 80.0, 60.0), 0.0);
        assertEquals(0.0, ConverterElement.stageEmf(Double.NaN, false, 48.0, 60.0), 0.0);
        assertEquals(0.0, ConverterElement.stageEmf(-5.0, false, 48.0, 60.0), 0.0);

        // The 0-state array is structurally untouched by staging (staging takes no state).
        double[] witness = ConverterElement.newStateArray();
        ConverterElement.stageDemandWatts(90.0, 0.9, false, 48.0);
        ConverterElement.stageEmf(12.0, false, 48.0, 60.0);
        ConverterElement.tripNext(false, 20.0, false, false, false);
        assertEquals(0, witness.length);
    }

    @Test
    void tripLatchAndCauseTable() {
        assertFalse(ConverterElement.tripNext(false, 20.0, false, false, false));
        assertTrue(ConverterElement.tripNext(false, 125.0, false, false, false));
        assertTrue(ConverterElement.tripNext(false, 200.0, false, false, false));
        assertTrue(ConverterElement.tripNext(false, 20.0, true, false, false));
        assertTrue(ConverterElement.tripNext(false, 20.0, false, true, false));
        assertTrue(ConverterElement.tripNext(false, 20.0, false, false, true));
        // Latched trips never clear through the trip path (reset is a separate BE action).
        assertTrue(ConverterElement.tripNext(true, 20.0, false, false, false));

        assertEquals(4, ConverterElement.advanceCounter(3, true));
        assertEquals(0, ConverterElement.advanceCounter(3, false));
        assertEquals(ConverterElement.UVLO_TRIP_TICKS,
            ConverterElement.advanceCounter(ConverterElement.UVLO_TRIP_TICKS - 1, true));
        assertEquals(ConverterElement.ANTI_ISLAND_TRIP_TICKS,
            ConverterElement.advanceCounter(ConverterElement.ANTI_ISLAND_TRIP_TICKS - 1, true));
    }

    // ---- source classification ----

    @Test
    void isActiveSourceAndAcTable() {
        // Active whenever untripped with a positive staged EMF.
        assertTrue(ConverterElement.isActiveSource(false, 12.0));
        assertFalse(ConverterElement.isActiveSource(true, 12.0));
        assertFalse(ConverterElement.isActiveSource(false, 0.0));
        assertFalse(ConverterElement.isActiveSource(false, -1.0));

        // Type table: DC-DC (0) DC, inverter (1) AC, transformer (2) AC,
        // rectifier (3) DC, EU bridge (4) DC-equivalent (output pair reserved open).
        assertFalse(ConverterElement.isACOutput(ConverterElement.TYPE_KIND_DC_DC));
        assertTrue(ConverterElement.isACOutput(ConverterElement.TYPE_KIND_INVERTER));
        assertTrue(ConverterElement.isACOutput(ConverterElement.TYPE_KIND_TRANSFORMER));
        assertFalse(ConverterElement.isACOutput(ConverterElement.TYPE_KIND_RECTIFIER));
        assertFalse(ConverterElement.isACOutput(ConverterElement.TYPE_KIND_EU));
        assertFalse(ConverterElement.isACOutput(99));
    }

    // ---- state / NBT contract ----

    @Test
    void emptyStateArrayContract() {
        double[] fresh = ConverterElement.newStateArray();
        assertEquals(0, fresh.length);
        assertNotSame(ConverterElement.newStateArray(), ConverterElement.newStateArray());

        double[] snap = ConverterElement.snapshotState(fresh);
        assertEquals(0, snap.length);
        assertNotSame(fresh, snap);

        ConverterElement.assignState(fresh, new double[0]);
        assertThrows(IllegalArgumentException.class,
            () -> ConverterElement.assignState(new double[1], new double[0]));
        assertThrows(IllegalArgumentException.class,
            () -> ConverterElement.assignState(new double[0], new double[1]));
        assertThrows(NullPointerException.class,
            () -> ConverterElement.snapshotState(null));
        assertThrows(IllegalArgumentException.class,
            () -> ConverterElement.snapshotState(new double[1]));
        assertThrows(NullPointerException.class,
            () -> ConverterElement.resetTelemetry(null));
        assertThrows(IllegalArgumentException.class,
            () -> new ConverterElement(() -> false, () -> 1.0, () -> 1.0, () -> 48.0, new double[3]));
    }

    @Test
    void nbtRoundTrip() {
        Map<String, Object> store = new HashMap<>();
        ConverterElement.writeNbt(writeFake(store), true);
        assertTrue(store.containsKey("stateArray"));
        assertTrue(store.containsKey("tripped"));
        assertEquals(0, ConverterElement.readNbtState(readFake(store)).length);
        assertTrue(ConverterElement.readNbtBlown(readFake(store)));

        Map<String, Object> clear = new HashMap<>();
        ConverterElement.writeNbt(writeFake(clear), false);
        assertFalse(ConverterElement.readNbtBlown(readFake(clear)));

        // Discrete flag never lives inside the state slice: identical (empty) either way.
        assertArrayEquals(ConverterElement.readNbtState(readFake(store)),
            ConverterElement.readNbtState(readFake(clear)), 0.0);

        // Absent keys default to a fresh untripped converter.
        assertEquals(0, ConverterElement.readNbtState(readFake(new HashMap<>())).length);
        assertFalse(ConverterElement.readNbtBlown(readFake(new HashMap<>())));
    }

    @Test
    void terminalOffsetsAreFourDistinctAdjacent() {
        int[][] o = ConverterElement.TERMINAL_OFFSETS;
        assertEquals(4, o.length);
        assertArrayEquals(new int[]{1, 0, 0}, o[0]);
        assertArrayEquals(new int[]{-1, 0, 0}, o[1]);
        assertArrayEquals(new int[]{0, 0, -1}, o[2]);
        assertArrayEquals(new int[]{0, 0, 1}, o[3]);
        for (int[] t : o) {
            assertEquals(1, Math.abs(t[0]) + Math.abs(t[1]) + Math.abs(t[2]));
        }
        for (int a = 0; a < o.length; a++) {
            for (int b = a + 1; b < o.length; b++) {
                assertFalse(o[a][0] == o[b][0] && o[a][1] == o[b][1] && o[a][2] == o[b][2]);
            }
        }
    }

    // ---- fallback rollback contract: per-island discard vs commit ----

    @Test
    void fallbackDiscardsTelemetryCache() {
        // A fallback tick's partial telemetry must never feed the next discrete phase:
        // the topology owner resets the write-only cache and keeps the BE copies.
        double[] tele = {12.5, 48.1, 11.9, 1.04};
        ConverterElement.resetTelemetry(tele);
        assertArrayEquals(new double[4], tele, 0.0);
        assertThrows(IllegalArgumentException.class,
            () -> ConverterElement.resetTelemetry(new double[3]));
        // Kernel state for converters is empty, so the re-sync half of rollback is a no-op.
        assertEquals(0, ConverterElement.newStateArray().length);
    }

    // ---- island independence: converter omega/efficiency never leaks ----

    @Test
    void islandsSolveIndependently() {
        // Island A (DC): 48 V feed -> converter input pair, 12 ohm load across the output pair.
        Staged conv = new Staged();
        conv.demand[0] = 100.0;
        conv.emf[0] = 12.0;
        conv.vnom[0] = 48.0;
        ElectricalKernel islandA = new ElectricalKernel();
        islandA.setNodeCount(4);
        islandA.setOmega(0.0);
        islandA.setElements(
            List.of(new TestThevenin(48.0, 0.05), conv.element(), new TestResistor(12.0),
                new EarthBlockEntity.EarthElement()),
            List.of(new int[]{0, 1}, new int[]{0, 1, 2, 3}, new int[]{2, 3}, new int[]{3}));
        islandA.setConductors(List.of());

        KernelSolveResult solvedA = islandA.solve();
        assertTrue(solvedA.converged(), "converter island must converge, residual=" + solvedA.residual());
        assertFalse(solvedA.singular());
        assertFalse(solvedA.fallbackActive());
        // Output Thevenin 12 V / 0.05 ohm into 12 ohm: 12*12/12.05 == 11.95 V.
        assertEquals(11.95, outputDifferential(solvedA.voltage()), 0.05);
        // Input CP 100 W on a stiff 48 V feed sags it by ~0.1 V.
        double vInA = inputDifferential(solvedA.voltage());
        assertTrue(vInA > 47.0 && vInA < 48.5, "input differential=" + vInA);

        // Island B (AC omega): plain 24 V source + 6 ohm load, no converter anywhere.
        ElectricalKernel islandB = new ElectricalKernel();
        islandB.setNodeCount(2);
        islandB.setOmega(GridConstants.AC_OMEGA_RAD_PER_S);
        islandB.setElements(
            List.of(new TestThevenin(24.0, 0.1), new TestResistor(6.0),
                new EarthBlockEntity.EarthElement()),
            List.of(new int[]{0, 1}, new int[]{0, 1}, new int[]{1}));
        islandB.setConductors(List.of());

        KernelSolveResult solvedB = islandB.solve();
        assertTrue(solvedB.converged());
        double vB = solvedB.voltage()[0].re - solvedB.voltage()[1].re;
        assertEquals(24.0 * 6.0 / 6.1, vB, 1e-6);
        // Resistor/Thevenin stamps are omega-independent: no imaginary content.
        assertEquals(0.0, solvedB.voltage()[0].im, 1e-12);
        assertEquals(0.0, solvedB.voltage()[1].im, 1e-12);

        // Restage the converter (as a BE efficiency change would through demand/EMF staging;
        // efficiency itself never enters the stamp) and re-solve: A moves, B is bit-identical.
        conv.demand[0] = 400.0;
        conv.emf[0] = 24.0;
        KernelSolveResult solvedA2 = islandA.solve();
        assertTrue(solvedA2.converged());
        assertFalse(solvedA2.fallbackActive());
        assertEquals(24.0 * 12.0 / 12.05, outputDifferential(solvedA2.voltage()), 0.05);

        KernelSolveResult solvedB2 = islandB.solve();
        assertTrue(solvedB2.converged());
        assertEquals(vB, solvedB2.voltage()[0].re - solvedB2.voltage()[1].re, 0.0);
    }

    // ---- kernel tick integrates telemetry the discrete phase then consumes ----

    @Test
    void kernelTickCachesTelemetryForStaging() {
        // End-to-end through the kernel: tick() solves, derivatives() caches output power,
        // and the next discrete phase would stage demand = cached/eff + idle.
        Staged conv = new Staged();
        conv.demand[0] = 100.0;
        conv.emf[0] = 12.0;
        conv.vnom[0] = 48.0;
        ElectricalKernel k = new ElectricalKernel();
        k.setNodeCount(4);
        k.setOmega(0.0);
        k.setElements(
            List.of(new TestThevenin(48.0, 0.05), conv.element(), new TestResistor(12.0),
                new EarthBlockEntity.EarthElement()),
            List.of(new int[]{0, 1}, new int[]{0, 1, 2, 3}, new int[]{2, 3}, new int[]{3}));
        k.setConductors(List.of());
        k.tick();

        double cached = conv.tele[ConverterElement.TELE_P_OUT];
        // Delivered into 12 ohm at ~11.95 V: ~11.9 W.
        assertTrue(cached > 11.0 && cached < 13.0, "cached output power=" + cached);
        assertEquals(48.0, conv.tele[ConverterElement.TELE_V_IN], 0.5);
        assertEquals(11.95, conv.tele[ConverterElement.TELE_V_OUT], 0.05);

        double staged = ConverterElement.stageDemandWatts(
            cached, 0.9, false, conv.tele[ConverterElement.TELE_V_IN]);
        assertEquals(cached / 0.9 + 2.0, staged, 1e-9);
    }
}
