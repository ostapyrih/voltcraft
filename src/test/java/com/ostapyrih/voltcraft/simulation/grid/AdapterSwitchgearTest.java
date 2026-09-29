package com.ostapyrih.voltcraft.simulation.grid;

import com.ostapyrih.voltcraft.api.electrical.Complex;
import com.ostapyrih.voltcraft.api.electrical.ElectricalElement;
import com.ostapyrih.voltcraft.api.electrical.GridConstants;
import com.ostapyrih.voltcraft.api.electrical.Stamps;
import com.ostapyrih.voltcraft.simulation.grid.ElectricalKernel.KernelSolveResult;
import com.ostapyrih.voltcraft.simulation.solver.ComplexNodalSolver;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.BlockPos;
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
import com.ostapyrih.voltcraft.simulation.electrical.BreakerElement;
import com.ostapyrih.voltcraft.simulation.electrical.BusbarElement;
import com.ostapyrih.voltcraft.simulation.electrical.ContactorElement;
import com.ostapyrih.voltcraft.simulation.electrical.EarthElement;
import com.ostapyrih.voltcraft.simulation.electrical.FuseElement;
import com.ostapyrih.voltcraft.simulation.electrical.SpliceElement;
import com.ostapyrih.voltcraft.simulation.electrical.SwitchElement;

/**
 * Static-block adapter tests: earth + switchgear kernel adapters (knife switch,
 * contactor relay, busbar, junction splice, circuit breaker, fuse).
 *
 * <p>Pure Java + kernel + adapter logic, no server, no registries. A hard
 * environment constraint shapes this suite: {@code BlockEntity.&lt;clinit&gt;}
 * touches {@code Registries} (verified against 1.21.11 bytecode), and
 * {@code Bootstrap.initialize()} itself crashes in plain unit-test runtimes
 * (vanilla registry code needs Fabric access-wideners, which only the mod loader
 * applies). Hence no test here may load/initialize a {@code BlockEntity} subclass
 * — the same reason the entire existing suite is registry-free.</p>
 *
 * <p>Consequently every branch of switchgear adapter logic (stamps, derivatives, discrete
 * transitions, state validation/copy, NBT bodies, terminal offsets, defaults) lives
 * in the static nested adapter classes
 * ({@code EarthElement}, {@code SwitchElement}, {@code ContactorElement},
 * {@code BusbarElement}, {@code SpliceElement}, {@code BreakerElement},
 * {@code FuseElement}), which initialize independently of their enclosing BE class
 * and are driven directly here with supplier-injected discrete cells. The outer BE
 * classes are thin glue (BE-owned fields, one-line delegates, vanilla NBT
 * overrides) mirroring the covered statics; that glue is the documented coverage
 * boundary. Only compile-time constants and nested classes of the BE files are
 * referenced — never anything that would initialize the outer BE class.</p>
 *
 * <p>Note on topology: "closed = conducting / open = isolated" is demonstrated with
 * a 3-node series loop (source {0,2}, device {0,1}, load {1,2}, earth {2}). A 2-node
 * all-parallel network cannot demonstrate isolation (the load stays fed by the source
 * regardless of the switch), so the series loop is the deliberate interpretation.</p>
 */
class AdapterSwitchgearTest {

    // ---- local fixtures (mirrors LinearCircuitTest; sources/loads are out of switchgear scope) ----

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
        return (WriteView) Proxy.newProxyInstance(AdapterSwitchgearTest.class.getClassLoader(),
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
        return (ReadView) Proxy.newProxyInstance(AdapterSwitchgearTest.class.getClassLoader(),
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
                }
                throw new UnsupportedOperationException("ReadView fake: " + name);
            });
    }

    // ---- kernel helpers: 3-node series loop source{0,2} / device{0,1} / load{1,2} / earth{2} ----

    private static final double SOURCE_V = 12.0;
    private static final double SOURCE_R = 0.01;
    private static final double LOAD_R = 10.0;

    private static ElectricalKernel seriesKernel(ElectricalElement device, double loadR) {
        ElectricalKernel k = new ElectricalKernel();
        k.setNodeCount(3);
        k.setOmega(0.0);
        k.setElements(
            List.of(new TestThevenin(SOURCE_V, SOURCE_R), device, new TestResistor(loadR),
                new EarthElement()),
            List.of(new int[]{0, 2}, new int[]{0, 1}, new int[]{1, 2}, new int[]{2}));
        k.setConductors(List.of());
        return k;
    }

    private static double loadCurrent(ElectricalKernel k, int loadIndex) {
        KernelSolveResult r = k.solve();
        assertTrue(r.converged(), "expected convergence, residual=" + r.residual());
        assertFalse(r.singular());
        return k.terminalCurrents(loadIndex)[0].magnitude();
    }

    // ---- stamp / derivatives contract ----

    @Test
    void earthStampAddsShuntConductance() {
        EarthElement earth = new EarthElement();
        assertEquals(1, earth.terminalCount());
        assertEquals(0, earth.stateCount());

        Complex[][] y = ComplexNodalSolver.zeroMatrix(1);
        Complex[] inj = ComplexNodalSolver.zeroVector(1);
        earth.stamp(y, inj, new int[]{0}, ComplexNodalSolver.zeroVector(1), new double[0], 0.0);
        assertEquals(EarthElement.G_EARTH_S, y[0][0].re, 1e-12);
        assertEquals(0.0, y[0][0].im, 1e-12);
        assertEquals(0.0, inj[0].magnitude(), 0.0);

        // Kernel level: single earthed node solves to ~0 V.
        ElectricalKernel k = new ElectricalKernel();
        k.setNodeCount(1);
        k.setOmega(0.0);
        k.setElements(List.of(earth), List.of(new int[]{0}));
        k.setConductors(List.of());
        KernelSolveResult r = k.solve();
        assertTrue(r.converged());
        assertTrue(r.voltage()[0].magnitude() < 1e-6, "earth node=" + r.voltage()[0]);
    }

    @Test
    void closedKnifeStampsSeriesAdmittance() {
        boolean[] closed = {true};
        SwitchElement sw =
            new SwitchElement(() -> closed[0]);
        Complex[][] y = ComplexNodalSolver.zeroMatrix(2);
        sw.stamp(y, ComplexNodalSolver.zeroVector(2), new int[]{0, 1},
            ComplexNodalSolver.zeroVector(2), new double[0], 0.0);
        double g = 1.0 / SwitchElement.R_CLOSED_OHM;
        assertEquals(g, y[0][0].re, 1e-9);
        assertEquals(g, y[1][1].re, 1e-9);
        assertEquals(-g, y[0][1].re, 1e-9);
        assertEquals(-g, y[1][0].re, 1e-9);
    }

    @Test
    void openKnifeStampsNothing() {
        boolean[] closed = {false};
        SwitchElement sw =
            new SwitchElement(() -> closed[0]);
        Complex[][] y = ComplexNodalSolver.zeroMatrix(2);
        sw.stamp(y, ComplexNodalSolver.zeroVector(2), new int[]{0, 1},
            ComplexNodalSolver.zeroVector(2), new double[0], 0.0);
        for (int a = 0; a < 2; a++) {
            for (int b = 0; b < 2; b++) {
                assertEquals(0.0, y[a][b].magnitude(), 0.0);
            }
        }
    }

    @Test
    void contactorDefaultOpenClosedStamps() {
        assertFalse(ContactorElement.DEFAULT_CLOSED);
        boolean[] closed = {ContactorElement.DEFAULT_CLOSED};
        ContactorElement relay =
            new ContactorElement(() -> closed[0]);
        Complex[][] y = ComplexNodalSolver.zeroMatrix(2);
        relay.stamp(y, ComplexNodalSolver.zeroVector(2), new int[]{0, 1},
            ComplexNodalSolver.zeroVector(2), new double[0], 0.0);
        assertEquals(0.0, y[0][0].magnitude(), 0.0);

        closed[0] = true;
        Complex[][] y2 = ComplexNodalSolver.zeroMatrix(2);
        relay.stamp(y2, ComplexNodalSolver.zeroVector(2), new int[]{0, 1},
            ComplexNodalSolver.zeroVector(2), new double[0], 0.0);
        double g = 1.0 / ContactorElement.R_CLOSED_OHM;
        assertEquals(g, y2[0][0].re, 1e-9);
        assertEquals(-g, y2[0][1].re, 1e-9);
    }

    @Test
    void busbarAndJunctionAlwaysStampLowResistance() {
        BusbarElement bb = new BusbarElement();
        SpliceElement jb = new SpliceElement();
        Complex[][] yb = ComplexNodalSolver.zeroMatrix(2);
        bb.stamp(yb, ComplexNodalSolver.zeroVector(2), new int[]{0, 1},
            ComplexNodalSolver.zeroVector(2), new double[0], 0.0);
        assertEquals(1.0 / BusbarElement.R_BUSBAR_OHM, yb[0][0].re, 1e-9);
        assertEquals(-1.0 / BusbarElement.R_BUSBAR_OHM, yb[0][1].re, 1e-9);

        Complex[][] yj = ComplexNodalSolver.zeroMatrix(2);
        jb.stamp(yj, ComplexNodalSolver.zeroVector(2), new int[]{0, 1},
            ComplexNodalSolver.zeroVector(2), new double[0], 0.0);
        assertEquals(1.0 / SpliceElement.R_SPLICE_OHM, yj[0][0].re, 1e-9);
        assertEquals(-1.0 / SpliceElement.R_SPLICE_OHM, yj[0][1].re, 1e-9);
    }

    @Test
    void breakerUntrippedStampsTrippedIsOpen() {
        assertFalse(BreakerElement.DEFAULT_TRIPPED);
        boolean[] tripped = {false};
        BreakerElement cb =
            new BreakerElement(() -> tripped[0]);
        Complex[][] y = ComplexNodalSolver.zeroMatrix(2);
        cb.stamp(y, ComplexNodalSolver.zeroVector(2), new int[]{0, 1},
            ComplexNodalSolver.zeroVector(2), new double[0], 0.0);
        assertEquals(1.0 / BreakerElement.R_CLOSED_OHM, y[0][0].re, 1e-9);

        tripped[0] = true;
        Complex[][] y2 = ComplexNodalSolver.zeroMatrix(2);
        cb.stamp(y2, ComplexNodalSolver.zeroVector(2), new int[]{0, 1},
            ComplexNodalSolver.zeroVector(2), new double[0], 0.0);
        assertEquals(0.0, y2[0][0].magnitude(), 0.0);
        assertEquals(0.0, y2[1][1].magnitude(), 0.0);
    }

    @Test
    void fuseIntactStampsBlownIsOpen() {
        boolean[] blown = {false};
        double[] tele = new double[1];
        FuseElement fuse =
            new FuseElement(() -> blown[0], tele);
        assertEquals(2, fuse.stateCount());
        Complex[][] y = ComplexNodalSolver.zeroMatrix(2);
        fuse.stamp(y, ComplexNodalSolver.zeroVector(2), new int[]{0, 1},
            ComplexNodalSolver.zeroVector(2),
            FuseElement.newStateArray(), 0.0);
        assertEquals(1.0 / FuseElement.R_FUSE_OHM, y[0][0].re, 1e-9);

        blown[0] = FuseElement.blowCheck(0.0, blown[0]);
        assertTrue(blown[0]);
        Complex[][] y2 = ComplexNodalSolver.zeroMatrix(2);
        fuse.stamp(y2, ComplexNodalSolver.zeroVector(2), new int[]{0, 1},
            ComplexNodalSolver.zeroVector(2), new double[]{85.0, 0.0}, 0.0);
        assertEquals(0.0, y2[0][0].magnitude(), 0.0);
        assertEquals(0.0, y2[1][1].magnitude(), 0.0);
    }

    @Test
    void blowCheckTruthTable() {
        assertFalse(FuseElement.blowCheck(1.0, false));
        assertFalse(FuseElement.blowCheck(0.5, false));
        assertTrue(FuseElement.blowCheck(0.0, false));
        assertTrue(FuseElement.blowCheck(-0.5, false));
        assertTrue(FuseElement.blowCheck(1.0, true), "blow latches");
    }

    @Test
    void zeroStateDerivativesAreNoOp() {
        boolean[] flag = {true};
        List<ElectricalElement> statics = List.of(
            new EarthElement(),
            new SwitchElement(() -> flag[0]),
            new ContactorElement(() -> flag[0]),
            new BusbarElement(),
            new SpliceElement(),
            new BreakerElement(() -> !flag[0]));
        for (ElectricalElement el : statics) {
            assertEquals(0, el.stateCount());
            // Must not throw on empty slices.
            el.derivatives(new double[0], new double[0], new Complex[0], new Complex[0]);
        }
    }

    @Test
    void fuseDerivativesNoDecayAtOrBelowRated() {
        boolean[] blown = {false};
        double[] tele = new double[1];
        FuseElement fuse =
            new FuseElement(() -> blown[0], tele);
        double[] state = {GridConstants.AMBIENT_C, 1.0};
        Complex[] vt = {new Complex(0.15, 0.0), new Complex(0.0, 0.0)};

        double[] dx = new double[2];
        fuse.derivatives(dx, state, vt,
            new Complex[]{new Complex(FuseElement.RATED_CURRENT_A, 0.0), new Complex(0.0, 0.0)});
        assertEquals(0.0, dx[FuseElement.STATE_INTEGRITY], 0.0);

        double[] dx2 = new double[2];
        fuse.derivatives(dx2, state, vt,
            new Complex[]{new Complex(10.0, 0.0), new Complex(0.0, 0.0)});
        assertEquals(0.0, dx2[FuseElement.STATE_INTEGRITY], 0.0);
        double expectedHeat = (10.0 * 10.0 * FuseElement.R_FUSE_OHM)
            / FuseElement.THERMAL_CAPACITY_J_PER_K;
        assertEquals(expectedHeat, dx2[FuseElement.STATE_TEMP], 1e-12);
        assertEquals(10.0, tele[0], 1e-12);
    }

    @Test
    void fuseDerivativesDecayOnOverload() {
        boolean[] blown = {false};
        double[] tele = new double[1];
        FuseElement fuse =
            new FuseElement(() -> blown[0], tele);
        double[] state = {GridConstants.AMBIENT_C, 1.0};
        Complex[] vt = {new Complex(0.3, 0.0), new Complex(0.0, 0.0)};
        double[] dx = new double[2];
        fuse.derivatives(dx, state, vt,
            new Complex[]{new Complex(60.0, 0.0), new Complex(0.0, 0.0)});
        double overload = 60.0 - FuseElement.RATED_CURRENT_A;
        assertEquals(-overload * overload * FuseElement.INTEGRITY_DECAY_K,
            dx[FuseElement.STATE_INTEGRITY], 1e-15);
        assertTrue(dx[FuseElement.STATE_INTEGRITY] < 0.0);
        double expectedHeat = (60.0 * 60.0 * FuseElement.R_FUSE_OHM)
            / FuseElement.THERMAL_CAPACITY_J_PER_K;
        assertEquals(expectedHeat, dx[FuseElement.STATE_TEMP], 1e-12);
        assertEquals(60.0, tele[0], 1e-12);
    }

    @Test
    void fuseDerivativesPureExceptTelemetryCache() {
        boolean[] blown = {false};
        double[] tele = new double[1];
        FuseElement fuse =
            new FuseElement(() -> blown[0], tele);
        double[] state = {50.0, 0.75};
        Complex[] vt = {new Complex(1.0, 0.0), new Complex(0.0, 0.0)};
        Complex[] it = {new Complex(500.0, 0.0), new Complex(-500.0, 0.0)};
        double[] first = new double[2];
        double[] second = new double[2];
        fuse.derivatives(first, state.clone(), vt.clone(), it.clone());
        fuse.derivatives(second, state.clone(), vt.clone(), it.clone());
        assertEquals(first[0], second[0], 0.0);
        assertEquals(first[1], second[1], 0.0);
        // Huge current through derivatives alone must not blow the fuse: the blown flag
        // is owned by the discrete phase (blowCheck) only.
        assertFalse(blown[0]);
        assertEquals(500.0, tele[0], 1e-12);
    }

    // ---- closed = conducting / open = isolated (kernel level) ----

    @Test
    void knifeOpenCloseChangesCurrentFlow() {
        boolean[] closed = {true};
        SwitchElement sw =
            new SwitchElement(() -> closed[0]);
        ElectricalKernel k = seriesKernel(sw, LOAD_R);

        double closedI = loadCurrent(k, 2);
        assertEquals(SOURCE_V / (SOURCE_R + SwitchElement.R_CLOSED_OHM + LOAD_R), closedI, 1e-4);

        closed[0] = false;
        double openI = loadCurrent(k, 2);
        assertTrue(openI < 1e-6, "open switch isolates load, got " + openI);

        closed[0] = true;
        assertEquals(closedI, loadCurrent(k, 2), 1e-9);
    }

    @Test
    void contactorClosedConductsOpenIsolates() {
        boolean[] closed = {false};
        ContactorElement relay =
            new ContactorElement(() -> closed[0]);
        ElectricalKernel k = seriesKernel(relay, LOAD_R);
        assertTrue(loadCurrent(k, 2) < 1e-6, "default-open contactor isolates");

        closed[0] = true;
        assertEquals(SOURCE_V / (SOURCE_R + ContactorElement.R_CLOSED_OHM + LOAD_R),
            loadCurrent(k, 2), 1e-4);
    }

    @Test
    void breakerUntrippedConductsTrippedIsolates() {
        boolean[] tripped = {false};
        BreakerElement cb =
            new BreakerElement(() -> tripped[0]);
        ElectricalKernel k = seriesKernel(cb, LOAD_R);
        assertEquals(SOURCE_V / (SOURCE_R + BreakerElement.R_CLOSED_OHM + LOAD_R),
            loadCurrent(k, 2), 1e-4);

        tripped[0] = true;
        assertTrue(loadCurrent(k, 2) < 1e-6, "tripped breaker isolates");
    }

    @Test
    void busbarAndJunctionAlwaysConduct() {
        assertEquals(SOURCE_V / (SOURCE_R + BusbarElement.R_BUSBAR_OHM + LOAD_R),
            loadCurrent(seriesKernel(new BusbarElement(), LOAD_R), 2), 1e-4);
        assertEquals(SOURCE_V / (SOURCE_R + SpliceElement.R_SPLICE_OHM + LOAD_R),
            loadCurrent(seriesKernel(new SpliceElement(), LOAD_R), 2), 1e-4);
    }

    @Test
    void earthProvidesGroundReference() {
        ElectricalKernel k = new ElectricalKernel();
        k.setNodeCount(2);
        k.setOmega(0.0);
        k.setElements(
            List.of(new TestThevenin(SOURCE_V, SOURCE_R), new TestResistor(LOAD_R),
                new EarthElement()),
            List.of(new int[]{0, 1}, new int[]{0, 1}, new int[]{1}));
        k.setConductors(List.of());
        KernelSolveResult r = k.solve();
        assertTrue(r.converged());
        assertTrue(r.voltage()[1].magnitude() < 1e-6, "earth node=" + r.voltage()[1]);
        double expected = SOURCE_V * LOAD_R / (LOAD_R + SOURCE_R);
        assertEquals(expected, r.voltage()[0].sub(r.voltage()[1]).magnitude(), 1e-3);
    }

    // ---- defensive copies (production assignState/snapshotState/checkState bodies) ----

    @Test
    void zeroStateCheckAndSnapshot() {
        // checkState accepts exactly empty/non-null; snapshot returns a fresh array per call.
        EarthElement.checkState(new double[0]);
        SwitchElement.checkState(new double[0]);
        ContactorElement.checkState(new double[0]);
        BusbarElement.checkState(new double[0]);
        SpliceElement.checkState(new double[0]);
        BreakerElement.checkState(new double[0]);

        assertThrows(IllegalArgumentException.class, () -> EarthElement.checkState(null));
        assertThrows(IllegalArgumentException.class,
            () -> SwitchElement.checkState(new double[]{1.0}));
        assertThrows(IllegalArgumentException.class,
            () -> ContactorElement.checkState(new double[]{1.0, 2.0}));
        assertThrows(IllegalArgumentException.class,
            () -> BusbarElement.checkState(null));
        assertThrows(IllegalArgumentException.class,
            () -> SpliceElement.checkState(new double[]{1.0}));
        assertThrows(IllegalArgumentException.class,
            () -> BreakerElement.checkState(new double[]{1.0}));

        assertEquals(0, EarthElement.snapshotState().length);
        assertNotSame(SwitchElement.snapshotState(),
            SwitchElement.snapshotState());
    }

    @Test
    void fuseAssignSnapshotAndValidation() {
        double[] fresh = FuseElement.newStateArray();
        assertEquals(2, fresh.length);
        assertEquals(GridConstants.AMBIENT_C, fresh[0], 0.0);
        assertEquals(1.0, fresh[1], 0.0);
        assertNotSame(FuseElement.newStateArray(),
            FuseElement.newStateArray());

        // snapshotState clones: mutating the returned array leaves the live one alone.
        double[] live = {50.0, 0.5};
        double[] snap = FuseElement.snapshotState(live);
        snap[0] = 999.0;
        assertEquals(50.0, live[0], 0.0);

        // assignState copies in: mutating the source afterwards leaves dst alone
        // (never retains the kernel array by reference).
        double[] dst = FuseElement.newStateArray();
        double[] src = {60.0, 0.25};
        FuseElement.assignState(dst, src);
        src[0] = 999.0;
        assertEquals(60.0, dst[0], 0.0);
        assertEquals(0.25, dst[1], 0.0);

        assertThrows(IllegalArgumentException.class,
            () -> FuseElement.assignState(null, new double[2]));
        assertThrows(IllegalArgumentException.class,
            () -> FuseElement.assignState(new double[2], null));
        assertThrows(IllegalArgumentException.class,
            () -> FuseElement.assignState(new double[2], new double[1]));
        assertThrows(IllegalArgumentException.class,
            () -> FuseElement.assignState(new double[0], new double[2]));
        assertThrows(IllegalArgumentException.class,
            () -> FuseElement.assignState(new double[3], new double[3]));
    }

    // ---- NBT round-trips (production writeNbt/readNbt bodies) ----

    @Test
    void knifeNbtRoundTrip() {
        assertTrue(SwitchElement.DEFAULT_CLOSED);
        Map<String, Object> store = new HashMap<>();
        SwitchElement.writeNbt(writeFake(store), false);
        assertTrue(store.containsKey(SwitchElement.KEY_STATE_ARRAY));
        assertEquals(false, SwitchElement.readNbtClosed(readFake(store)));

        // Absent flag falls back to the documented default.
        assertEquals(SwitchElement.DEFAULT_CLOSED,
            SwitchElement.readNbtClosed(readFake(new HashMap<>())));
    }

    @Test
    void contactorNbtRoundTrip() {
        Map<String, Object> store = new HashMap<>();
        ContactorElement.writeNbt(writeFake(store), true);
        assertTrue(ContactorElement.readNbtClosed(readFake(store)));
        assertEquals(ContactorElement.DEFAULT_CLOSED,
            ContactorElement.readNbtClosed(readFake(new HashMap<>())));
    }

    @Test
    void breakerNbtRoundTrip() {
        Map<String, Object> store = new HashMap<>();
        BreakerElement.writeNbt(writeFake(store), true);
        assertTrue(BreakerElement.readNbtTripped(readFake(store)));
        assertEquals(BreakerElement.DEFAULT_TRIPPED,
            BreakerElement.readNbtTripped(readFake(new HashMap<>())));
    }

    @Test
    void fuseNbtRoundTrip() {
        Map<String, Object> store = new HashMap<>();
        FuseElement.writeNbt(writeFake(store), 123.5, 0.42, false);
        double[] restored = FuseElement.readNbtState(readFake(store));
        assertEquals(123.5, restored[0], 0.0);
        assertEquals(0.42, restored[1], 0.0);
        assertFalse(FuseElement.readNbtBlown(readFake(store)));

        Map<String, Object> blownStore = new HashMap<>();
        FuseElement.writeNbt(writeFake(blownStore), 90.0, 0.0, true);
        assertTrue(FuseElement.readNbtBlown(readFake(blownStore)));
        assertEquals(0.0, FuseElement.readNbtState(readFake(blownStore))[1], 0.0);
    }

    @Test
    void staticDevicesNbtRoundTripWithoutThrowing() {
        Map<String, Object> earthStore = new HashMap<>();
        EarthElement.writeNbt(writeFake(earthStore));
        assertTrue(earthStore.containsKey(EarthElement.KEY_STATE_ARRAY));
        EarthElement.readNbt(readFake(earthStore));

        Map<String, Object> busStore = new HashMap<>();
        BusbarElement.writeNbt(writeFake(busStore));
        assertTrue(busStore.containsKey(BusbarElement.KEY_STATE_ARRAY));
        BusbarElement.readNbt(readFake(busStore));

        Map<String, Object> spliceStore = new HashMap<>();
        SpliceElement.writeNbt(writeFake(spliceStore));
        assertTrue(spliceStore.containsKey(SpliceElement.KEY_STATE_ARRAY));
        SpliceElement.readNbt(readFake(spliceStore));
    }

    @Test
    void fuseNbtIgnoresMalformedStateList() {
        Map<String, Object> store = new HashMap<>();
        store.put(FuseElement.KEY_STATE_ARRAY, List.of(1.0));
        store.put(FuseElement.KEY_BLOWN, true);
        double[] restored = FuseElement.readNbtState(readFake(store));
        // Malformed state keeps fresh-fuse defaults; the discrete flag still restores.
        assertEquals(GridConstants.AMBIENT_C, restored[0], 0.0);
        assertEquals(1.0, restored[1], 0.0);
        assertTrue(FuseElement.readNbtBlown(readFake(store)));
    }

    // ---- fuse lifecycle end to end ----

    @Test
    void blownFuseIsOpenCircuitAtKernelLevel() {
        boolean[] intactBlown = {false};
        double intactI = loadCurrent(
            seriesKernel(new FuseElement(() -> intactBlown[0], new double[1]), LOAD_R), 2);
        assertTrue(intactI > 1.0, "intact fuse conducts, got " + intactI);

        boolean[] deadBlown = {FuseElement.blowCheck(0.0, false)};
        double deadI = loadCurrent(
            seriesKernel(new FuseElement(() -> deadBlown[0], new double[1]), LOAD_R), 2);
        assertTrue(deadI < 1e-6, "blown fuse isolates, got " + deadI);
    }

    @Test
    void fuseOverloadBlowsEndToEndWithOneTickDelayTelemetry() {
        boolean[] blown = {false};
        double[] tele = new double[1];
        FuseElement fuse =
            new FuseElement(() -> blown[0], tele);
        ElectricalKernel k = seriesKernel(fuse, 0.01);
        // Seed kernel state from the BE copy (the topology owner must do this after every
        // setElements: the kernel zero-initializes, which would read as already-blown).
        k.setElementState(1, FuseElement.newStateArray());

        // BE-owned state copy in the test (production BE field); per-tick kernel->BE sync
        // followed by the discrete blow phase, exactly the GridManager protocol.
        double[] beState = FuseElement.newStateArray();
        int ticksToBlow = -1;
        for (int t = 0; t < 2000 && !blown[0]; t++) {
            k.tick();
            FuseElement.assignState(beState, k.getElementState(1));
            blown[0] = FuseElement.blowCheck(
                beState[FuseElement.STATE_INTEGRITY], blown[0]);
            ticksToBlow = t + 1;
        }
        assertTrue(blown[0], "16x overload must eventually blow the fuse");
        assertTrue(ticksToBlow > 10, "must not blow instantly (seeding contract), took " + ticksToBlow);
        assertTrue(ticksToBlow < 500, "expected ~99 ticks at 480 A, took " + ticksToBlow);
        assertTrue(beState[FuseElement.STATE_TEMP] > GridConstants.AMBIENT_C,
            "fuse heated while overloaded");
        assertTrue(tele[0] > 400.0, "telemetry observed the overload, got " + tele[0]);

        // After the blow the stamp is an open circuit: load current collapses.
        assertTrue(loadCurrent(k, 2) < 1e-6, "post-blow current must vanish");

        // One more tick lets derivatives observe the open circuit: telemetry follows with delay.
        k.tick();
        FuseElement.assignState(beState, k.getElementState(1));
        assertTrue(tele[0] < 1e-6, "telemetry current=" + tele[0]);
    }

    // ---- terminal conventions ----

    @Test
    void terminalOffsetsAreAdjacentWorldPositions() {
        BlockPos p = new BlockPos(60, 64, 0);
        assertArrayEquals(new int[][]{{0, -1, 0}}, EarthElement.TERMINAL_OFFSETS);
        assertEquals(p.down(), p.add(0, -1, 0));

        int[][][] twoTerminal = {
            SwitchElement.TERMINAL_OFFSETS,
            ContactorElement.TERMINAL_OFFSETS,
            BusbarElement.TERMINAL_OFFSETS,
            SpliceElement.TERMINAL_OFFSETS,
            BreakerElement.TERMINAL_OFFSETS,
            FuseElement.TERMINAL_OFFSETS,
        };
        for (int[][] offsets : twoTerminal) {
            assertEquals(2, offsets.length);
            // North / south convention shared by all two-terminal switchgear adapters.
            assertArrayEquals(new int[]{0, 0, -1}, offsets[0]);
            assertArrayEquals(new int[]{0, 0, 1}, offsets[1]);
            for (int[] o : offsets) {
                BlockPos t = p.add(o[0], o[1], o[2]);
                int manhattan = Math.abs(t.getX() - p.getX()) + Math.abs(t.getY() - p.getY())
                    + Math.abs(t.getZ() - p.getZ());
                assertEquals(1, manhattan, "terminal must be adjacent: " + t);
            }
        }
        assertEquals(p.north(), p.add(0, 0, -1));
        assertEquals(p.south(), p.add(0, 0, 1));
    }
}
