package com.ostapyrih.voltcraft.simulation.grid;

import com.ostapyrih.voltcraft.api.electrical.Complex;
import com.ostapyrih.voltcraft.api.electrical.ElectricalElement;
import com.ostapyrih.voltcraft.api.electrical.GridConstants;
import com.ostapyrih.voltcraft.api.electrical.Stamps;
import com.ostapyrih.voltcraft.simulation.electrical.CrankElement;
import com.ostapyrih.voltcraft.simulation.electrical.GeneratorElement;
import com.ostapyrih.voltcraft.simulation.electrical.SolarElement;
import com.ostapyrih.voltcraft.simulation.electrical.BatteryElement;
import com.ostapyrih.voltcraft.simulation.electrical.RackElement;
import com.ostapyrih.voltcraft.simulation.chemistry.BatteryChemistry;
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
 * Source adapter tests: stateful source/storage kernel adapters (battery,
 * rack, solar, fuel and crank generators).
 *
 * <p>Pure Java + kernel + adapter logic, no server, no registries. The same hard
 * environment constraint as in the switchgear suite applies: {@code BlockEntity.&lt;clinit&gt;} touches
 * {@code Registries}, so no test may load/initialize an outer {@code BlockEntity}
 * subclass. Every branch of source adapter logic (stamps, derivatives, discrete BMS/active
 * transitions, state validation/copy, NBT bodies, terminal offsets, defaults) lives in
 * the static nested adapter classes ({@code BatteryElement}, {@code RackElement},
 * {@code SolarElement}, {@code GeneratorElement}, {@code CrankElement}), which
 * initialize independently of their enclosing BE class and are driven directly here
 * with supplier-injected discrete cells. Only compile-time constants and nested classes
 * of the BE files are referenced — never anything that would initialize the outer BE
 * class. The outer glue (BE-owned fields, one-line delegates, vanilla NBT overrides,
 * {@code tickElectrical} bodies mirroring the covered statics 1:1) is the documented
 * coverage boundary.</p>
 *
 * <p>Polarity note: all source adapters stamp the Thevenin EMF with
 * {@code terminals[1]} (south) positive, so {@code It[0]} is positive while the element
 * delivers power and battery {@code dSoc/dt = -It[0]/Q} holds.</p>
 */
class AdapterSourcesTest {

    // ---- local fixtures ----

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
        return (WriteView) Proxy.newProxyInstance(AdapterSourcesTest.class.getClassLoader(),
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
        return (ReadView) Proxy.newProxyInstance(AdapterSourcesTest.class.getClassLoader(),
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

    private static double[] stampI(ElectricalElement el, double[] state, int n) {
        Complex[][] y = ComplexNodalSolver.zeroMatrix(n);
        Complex[] inj = ComplexNodalSolver.zeroVector(n);
        el.stamp(y, inj, new int[]{0, 1}, ComplexNodalSolver.zeroVector(n), state, 0.0);
        return new double[]{y[0][0].re, y[0][1].re, y[1][0].re, y[1][1].re, inj[0].re, inj[1].re};
    }

    private static double loadCurrent(ElectricalKernel k, int loadIndex) {
        KernelSolveResult r = k.solve();
        assertTrue(r.converged(), "expected convergence, residual=" + r.residual());
        assertFalse(r.singular());
        return k.terminalCurrents(loadIndex)[0].magnitude();
    }

    // ---- stamp Y/I for known V (hand-computed Thevenin values) ----

    @Test
    void batteryStampTheveninValues() {
        // LIFEPO4 4s1p at soc=1, 25 C, health=1: OCV = full 3.65 V exact (curve hits 1.0 at s=1),
        // temp/health/soc factors all 1.0, so R = 0.0006 * 4 = 0.0024 ohm, EMF = 14.6 V.
        boolean[] bms = {false};
        double[] tele = new double[2];
        BatteryElement batt = new BatteryElement(BatteryChemistry.LIFEPO4, 4, 1, () -> bms[0], tele);
        assertEquals(2, batt.terminalCount());
        assertEquals(3, batt.stateCount());

        double g = 1.0 / 0.0024;
        double emf = 14.6;
        double[] y = stampI(batt, new double[]{1.0, 25.0, 1.0}, 2);
        assertEquals(g, y[0], 1e-9);
        assertEquals(-g, y[1], 1e-9);
        assertEquals(-g, y[2], 1e-9);
        assertEquals(g, y[3], 1e-9);
        // Positive terminal is terminals[1]: Norton current injected into node 1.
        assertEquals(-g * emf, y[4], 1e-6);
        assertEquals(g * emf, y[5], 1e-6);

        bms[0] = true;
        double[] open = stampI(batt, new double[]{1.0, 25.0, 1.0}, 2);
        for (double v : open) {
            assertEquals(0.0, v, 0.0);
        }
    }

    @Test
    void rackStampTheveninValues() {
        // 4 cells series, 18650 default: OCV(soc=1) = 4.2 V (smooth cubic hits 1.0 at s=1),
        // R = 0.025 * 4 = 0.1 ohm, EMF = 16.8 V.
        int[] count = {4};
        boolean[] series = {true};
        boolean[] bms = {false};
        RackElement rack = new RackElement(() -> count[0], () -> series[0], () -> bms[0], new double[2]);
        assertEquals(2, rack.terminalCount());
        assertEquals(3, rack.stateCount());
        assertEquals(4, rack.stagedSeries());
        assertEquals(1, rack.stagedParallel());

        double[] y = stampI(rack, new double[]{1.0, 25.0, 1.0}, 2);
        assertEquals(10.0, y[0], 1e-9);
        assertEquals(-10.0, y[1], 1e-9);
        assertEquals(-168.0, y[4], 1e-9);
        assertEquals(168.0, y[5], 1e-9);

        series[0] = false;
        assertEquals(1, rack.stagedSeries());
        assertEquals(4, rack.stagedParallel());

        count[0] = 0;
        double[] empty = stampI(rack, new double[]{1.0, 25.0, 1.0}, 2);
        for (double v : empty) {
            assertEquals(0.0, v, 0.0);
        }
    }

    @Test
    void solarStampTheveninValues() {
        double[] emfCell = {48.0};
        double[] rCell = {0.8};
        double[] irrCell = {1000.0};
        SolarElement solar = new SolarElement(() -> emfCell[0], () -> rCell[0], () -> irrCell[0],
            new double[2]);
        assertEquals(2, solar.terminalCount());
        assertEquals(1, solar.stateCount());

        double[] y = stampI(solar, new double[]{25.0}, 2);
        assertEquals(1.25, y[0], 1e-12);
        assertEquals(-1.25, y[1], 1e-12);
        assertEquals(-60.0, y[4], 1e-9);
        assertEquals(60.0, y[5], 1e-9);

        emfCell[0] = 0.0;
        double[] night = stampI(solar, new double[]{25.0}, 2);
        for (double v : night) {
            assertEquals(0.0, v, 0.0);
        }
    }

    @Test
    void generatorStampTheveninValues() {
        boolean[] running = {true};
        GeneratorElement gen = new GeneratorElement(() -> running[0], new double[2]);
        assertEquals(2, gen.terminalCount());
        assertEquals(2, gen.stateCount());

        double g = 1.0 / 0.15;
        double[] y = stampI(gen, new double[]{25.0, 100.0}, 2);
        assertEquals(g, y[0], 1e-9);
        assertEquals(-g, y[1], 1e-9);
        assertEquals(-g * 230.0, y[4], 1e-6);
        assertEquals(g * 230.0, y[5], 1e-6);

        running[0] = false;
        double[] dry = stampI(gen, new double[]{25.0, 0.0}, 2);
        for (double v : dry) {
            assertEquals(0.0, v, 0.0);
        }
    }

    @Test
    void crankStampTheveninValues() {
        CrankElement crank = new CrankElement(new double[2]);
        assertEquals(2, crank.terminalCount());
        assertEquals(2, crank.stateCount());

        // Full speed: EMF = 13.8 V, R = 0.15 ohm, lamp current 92 A into a short.
        double[] y = stampI(crank, new double[]{1.0, 0.0}, 2);
        assertEquals(1.0 / 0.15, y[0], 1e-9);
        assertEquals(-92.0, y[4], 1e-9);
        assertEquals(92.0, y[5], 1e-9);
        assertEquals(CrankElement.emfForSpeed(1.0), 13.8, 0.0);
        assertEquals(CrankElement.emfForSpeed(0.5), 6.9, 1e-12);

        double[] still = stampI(crank, new double[]{0.0, 0.0}, 2);
        for (double v : still) {
            assertEquals(0.0, v, 0.0);
        }
    }

    // ---- NBT round-trips: stateArray + discrete outside the state ----

    @Test
    void batteryNbtRoundTrip() {
        Map<String, Object> store = new HashMap<>();
        BatteryElement.writeNbt(writeFake(store), 0.42, 33.5, 0.9, true);
        assertTrue(store.containsKey("stateArray"));
        assertTrue(store.containsKey("bmsOpen"));

        double[] restored = BatteryElement.readNbtState(readFake(store));
        assertEquals(0.42, restored[0], 0.0);
        assertEquals(33.5, restored[1], 0.0);
        assertEquals(0.9, restored[2], 0.0);
        assertTrue(BatteryElement.readNbtBmsOpen(readFake(store)));

        // Discrete flag never lives inside the state slice: same state, either flag.
        Map<String, Object> open = new HashMap<>();
        Map<String, Object> closed = new HashMap<>();
        BatteryElement.writeNbt(writeFake(open), 0.5, 25.0, 1.0, true);
        BatteryElement.writeNbt(writeFake(closed), 0.5, 25.0, 1.0, false);
        assertArrayEquals(BatteryElement.readNbtState(readFake(open)),
            BatteryElement.readNbtState(readFake(closed)), 0.0);
        assertEquals(3, BatteryElement.readNbtState(readFake(open)).length);

        // Malformed list falls back to fresh-pack defaults; flag still restores.
        Map<String, Object> bad = new HashMap<>();
        bad.put("stateArray", List.of(1.0, 2.0));
        bad.put("bmsOpen", true);
        assertArrayEquals(BatteryElement.newStateArray(),
            BatteryElement.readNbtState(readFake(bad)), 0.0);
        assertTrue(BatteryElement.readNbtBmsOpen(readFake(bad)));
        assertFalse(BatteryElement.readNbtBmsOpen(readFake(new HashMap<>())));
    }

    @Test
    void rackNbtRoundTrip() {
        Map<String, Object> store = new HashMap<>();
        RackElement.writeNbt(writeFake(store), 0.6, 30.0, 0.95, false, false);
        double[] restored = RackElement.readNbtState(readFake(store));
        assertEquals(0.6, restored[0], 0.0);
        assertEquals(30.0, restored[1], 0.0);
        assertEquals(0.95, restored[2], 0.0);
        assertFalse(RackElement.readNbtBmsOpen(readFake(store)));
        assertFalse(RackElement.readNbtSeriesMode(readFake(store)));

        Map<String, Object> seriesStore = new HashMap<>();
        RackElement.writeNbt(writeFake(seriesStore), 0.6, 30.0, 0.95, true, true);
        assertTrue(RackElement.readNbtSeriesMode(readFake(seriesStore)));
        assertTrue(RackElement.readNbtBmsOpen(readFake(seriesStore)));
        assertEquals(3, RackElement.readNbtState(readFake(seriesStore)).length);

        // Absent wiring defaults to series; malformed state to fresh-rack defaults.
        assertTrue(RackElement.readNbtSeriesMode(readFake(new HashMap<>())));
        Map<String, Object> bad = new HashMap<>();
        bad.put("stateArray", List.of(1.0));
        assertArrayEquals(RackElement.newStateArray(),
            RackElement.readNbtState(readFake(bad)), 0.0);
    }

    @Test
    void solarNbtRoundTrip() {
        Map<String, Object> store = new HashMap<>();
        SolarElement.writeNbt(writeFake(store), 41.5, 12345.0);
        assertTrue(store.containsKey("stateArray"));
        assertTrue(store.containsKey("total_energy_generated"));
        // Staging inputs (irradiance/EMF/resistance) are transient: never persisted.
        assertFalse(store.containsKey("irradiance"));
        assertFalse(store.containsKey("electromotiveForce"));

        double[] restored = SolarElement.readNbtState(readFake(store));
        assertEquals(1, restored.length);
        assertEquals(41.5, restored[0], 0.0);
        assertEquals(12345.0, SolarElement.readNbtTotalEnergy(readFake(store)), 0.0);

        Map<String, Object> bad = new HashMap<>();
        bad.put("stateArray", List.of(1.0, 2.0));
        assertArrayEquals(SolarElement.newStateArray(),
            SolarElement.readNbtState(readFake(bad)), 0.0);
        assertEquals(0.0, SolarElement.readNbtTotalEnergy(readFake(new HashMap<>())), 0.0);
    }

    @Test
    void generatorNbtRoundTrip() {
        Map<String, Object> store = new HashMap<>();
        GeneratorElement.writeNbt(writeFake(store), 55.0, 500.0, 777.0);
        assertTrue(store.containsKey("stateArray"));
        assertTrue(store.containsKey("total_energy_joules"));

        double[] restored = GeneratorElement.readNbtState(readFake(store));
        assertEquals(2, restored.length);
        assertEquals(55.0, restored[0], 0.0);
        assertEquals(500.0, restored[1], 0.0);
        assertEquals(777.0, GeneratorElement.readNbtTotalEnergy(readFake(store)), 0.0);
    }

    @Test
    void crankNbtRoundTrip() {
        Map<String, Object> store = new HashMap<>();
        CrankElement.writeNbt(writeFake(store), 0.7, 321.0);
        assertTrue(store.containsKey("stateArray"));
        double[] restored = CrankElement.readNbtState(readFake(store));
        assertEquals(0.7, restored[0], 0.0);
        assertEquals(321.0, restored[1], 0.0);
    }

    @Test
    void stateAssignSnapshotValidation() {
        // Battery: clone-on-get, copy-on-set with [0,1] clamping, length validation.
        double[] live = {0.5, 30.0, 0.8};
        double[] snap = BatteryElement.snapshotState(live);
        snap[0] = 999.0;
        assertEquals(0.5, live[0], 0.0);

        double[] dst = BatteryElement.newStateArray();
        double[] src = {0.25, 40.0, 0.5};
        BatteryElement.assignState(dst, src);
        src[0] = 999.0;
        assertEquals(0.25, dst[0], 0.0);

        double[] clamp = BatteryElement.newStateArray();
        BatteryElement.assignState(clamp, new double[]{-0.5, 20.0, 1.5});
        assertEquals(0.0, clamp[0], 0.0);
        assertEquals(1.0, clamp[2], 0.0);

        assertThrows(IllegalArgumentException.class,
            () -> BatteryElement.assignState(new double[2], new double[3]));
        assertThrows(IllegalArgumentException.class,
            () -> BatteryElement.assignState(new double[3], new double[2]));
        assertThrows(IllegalArgumentException.class,
            () -> BatteryElement.assignState(null, new double[3]));
        assertNotSame(BatteryElement.newStateArray(), BatteryElement.newStateArray());

        // Rack shares the pack helpers; solar/generator/crank validate own lengths.
        double[] rackDst = RackElement.newStateArray();
        assertEquals(0.0, rackDst[0], 0.0);
        assertEquals(GridConstants.AMBIENT_C, rackDst[1], 0.0);
        BatteryElement.assignState(rackDst, new double[]{0.7, 21.0, 0.9});
        assertEquals(0.7, rackDst[0], 0.0);

        assertThrows(IllegalArgumentException.class,
            () -> SolarElement.assignState(new double[2], new double[2]));
        assertThrows(IllegalArgumentException.class,
            () -> GeneratorElement.assignState(new double[1], new double[1]));
        assertThrows(IllegalArgumentException.class,
            () -> CrankElement.assignState(new double[3], new double[3]));

        double[] crankDst = CrankElement.newStateArray();
        CrankElement.assignState(crankDst, new double[]{1.5, -10.0});
        assertEquals(1.0, crankDst[0], 0.0);
        assertEquals(0.0, crankDst[1], 0.0);
    }

    // ---- fallback rollback contract: per-island discard vs commit ----

    @Test
    void fallbackRollbackIsPerIsland() {
        // Island A: two stateful elements (battery + crank) plus load; node 0 is reference.
        boolean[] bmsA = {false};
        double[] teleBattA = new double[2];
        double[] teleCrankA = new double[2];
        BatteryElement battA = new BatteryElement(BatteryChemistry.LI_ION_18650, 4, 1,
            () -> bmsA[0], teleBattA);
        CrankElement crankA = new CrankElement(teleCrankA);
        ElectricalKernel islandA = new ElectricalKernel();
        islandA.setNodeCount(2);
        islandA.setOmega(0.0);
        islandA.setElements(
            List.of(battA, crankA, new TestResistor(10.0)),
            List.of(new int[]{0, 1}, new int[]{0, 1}, new int[]{0, 1}));
        islandA.setConductors(List.of());

        // Island B: independent single-battery island.
        boolean[] bmsB = {false};
        BatteryElement battB = new BatteryElement(BatteryChemistry.LI_ION_18650, 4, 1,
            () -> bmsB[0], new double[2]);
        ElectricalKernel islandB = new ElectricalKernel();
        islandB.setNodeCount(2);
        islandB.setOmega(0.0);
        islandB.setElements(
            List.of(battB, new TestResistor(10.0)),
            List.of(new int[]{0, 1}, new int[]{0, 1}));
        islandB.setConductors(List.of());

        // BE-owned snapshots (the topology owner seeds the kernel from these).
        double[] beBattA = {1.0, 25.0, 1.0};
        double[] beCrankA = {1.0, 0.0};
        double[] beBattB = {1.0, 25.0, 1.0};
        islandA.setElementState(0, beBattA);
        islandA.setElementState(1, beCrankA);
        islandB.setElementState(0, beBattB);

        islandA.tick();
        islandB.tick();
        // Both kernels integrated: states moved away from the seeds.
        assertFalse(islandA.getElementState(0)[0] == 1.0);
        assertFalse(islandA.getElementState(1)[0] == 1.0);

        // Island A reports fallbackActive: discard — re-sync kernel from BE snapshots,
        // BE copies untouched.
        boolean fallbackActiveA = true;
        if (fallbackActiveA) {
            islandA.setElementState(0, beBattA);
            islandA.setElementState(1, beCrankA);
        } else {
            BatteryElement.assignState(beBattA, islandA.getElementState(0));
            CrankElement.assignState(beCrankA, islandA.getElementState(1));
        }
        assertArrayEquals(new double[]{1.0, 25.0, 1.0}, islandA.getElementState(0), 0.0);
        assertArrayEquals(new double[]{1.0, 0.0}, islandA.getElementState(1), 0.0);
        assertArrayEquals(new double[]{1.0, 25.0, 1.0}, beBattA, 0.0);

        // Island B reports no fallback: commit — BE updated from the kernel.
        boolean fallbackActiveB = false;
        if (fallbackActiveB) {
            islandB.setElementState(0, beBattB);
        } else {
            BatteryElement.assignState(beBattB, islandB.getElementState(0));
        }
        assertTrue(beBattB[0] < 1.0, "committed island discharges, soc=" + beBattB[0]);
        // Light-load cooling toward ambient dominates Joule heating on tick 1 (25 C -> 20 C).
        assertTrue(beBattB[1] < 25.0, "committed island cools toward ambient, T=" + beBattB[1]);
    }

    // ---- isActiveSource classification table ----

    @Test
    void isActiveSourceClassification() {
        assertTrue(BatteryElement.isActiveSource(false));
        assertFalse(BatteryElement.isActiveSource(true));

        assertTrue(RackElement.isActiveSource(false, 4));
        assertFalse(RackElement.isActiveSource(true, 4));
        assertFalse(RackElement.isActiveSource(false, 0));

        assertTrue(SolarElement.isActiveSource(48.0));
        assertFalse(SolarElement.isActiveSource(0.0));
        assertFalse(SolarElement.isActiveSource(-1.0));

        assertTrue(GeneratorElement.isActiveSource(true));
        assertFalse(GeneratorElement.isActiveSource(false));

        assertTrue(CrankElement.isActiveSource(0.5));
        assertFalse(CrankElement.isActiveSource(0.0));
    }

    // ---- battery discharge into R: SoC falls, It[0] > 0, BMS opens at min V ----

    @Test
    void batteryDischargeDrawsSocDown() {
        boolean[] bms = {false};
        double[] tele = new double[2];
        BatteryElement batt = new BatteryElement(BatteryChemistry.LI_ION_18650, 4, 1,
            () -> bms[0], tele);
        ElectricalKernel k = new ElectricalKernel();
        k.setNodeCount(2);
        k.setOmega(0.0);
        k.setElements(
            List.of(batt, new TestResistor(10.0)),
            List.of(new int[]{0, 1}, new int[]{0, 1}));
        k.setConductors(List.of());
        double[] beState = {1.0, 25.0, 1.0};
        k.setElementState(0, beState);

        KernelSolveResult solved = k.solve();
        assertTrue(solved.converged());
        Complex[] it = k.terminalCurrents(0);
        // Consuming sign: current entering the negative terminal is positive on discharge.
        assertTrue(it[0].re > 1.0, "discharge current It[0]=" + it[0]);
        double vTerm = solved.voltage()[1].re - solved.voltage()[0].re;
        assertTrue(vTerm > 15.0 && vTerm < 17.0, "4s 18650 pack terminal V=" + vTerm);

        for (int t = 0; t < 200; t++) {
            k.tick();
        }
        BatteryElement.assignState(beState, k.getElementState(0));
        assertTrue(beState[0] < 1.0, "SoC must fall on discharge, soc=" + beState[0]);
        assertTrue(beState[0] > 0.99, "200 ticks at ~1.7 A barely dent 3 Ah, soc=" + beState[0]);
        // Telemetry observed delivery (write-only cache, previous-tick values).
        assertTrue(tele[BatteryElement.TELE_I] > 1.0, "telemetry I=" + tele[1]);
        assertTrue(tele[BatteryElement.TELE_V] > 15.0, "telemetry V=" + tele[0]);
    }

    @Test
    void bmsOpensAtMinVoltageWithHysteresis() {
        // Telemetry injection: drive derivatives with a sagging terminal voltage, then run
        // the pure discrete transition (the outer tickElectrical delegates to bmsNext 1:1
        // and is null-world safe by construction — it dereferences no world).
        boolean[] bms = {false};
        double[] tele = new double[2];
        BatteryElement batt = new BatteryElement(BatteryChemistry.LI_ION_18650, 4, 1,
            () -> bms[0], tele);
        double minPackV = BatteryElement.packMinVoltage(BatteryChemistry.LI_ION_18650, 4);
        assertEquals(11.2, minPackV, 1e-9);

        double[] state = {0.05, 25.0, 1.0};
        double[] dx = new double[3];
        // Inject a 5 V terminal (below the 11.2 V cutoff) with modest current.
        batt.derivatives(dx, state,
            new Complex[]{new Complex(0.0, 0.0), new Complex(5.0, 0.0)},
            new Complex[]{new Complex(2.0, 0.0), new Complex(-2.0, 0.0)});
        assertEquals(5.0, tele[BatteryElement.TELE_V], 1e-12);

        bms[0] = BatteryElement.bmsNext(bms[0], tele[BatteryElement.TELE_V],
            state[1], minPackV, 4);
        assertTrue(bms[0], "BMS must open below pack cutoff");

        // Recovery needs hysteresis: just above cutoff stays open.
        assertTrue(BatteryElement.bmsNext(true, minPackV + 0.1, 25.0, minPackV, 4));
        // Well above cutoff recloses.
        assertFalse(BatteryElement.bmsNext(true, minPackV + 0.21, 25.0, minPackV, 4));
        // Overtemp opens even at healthy voltage; reclose needs cooling below 55 C.
        assertTrue(BatteryElement.bmsNext(false, 16.0, 65.0, minPackV, 4));
        assertTrue(BatteryElement.bmsNext(true, 16.0, 57.0, minPackV, 4));
        assertFalse(BatteryElement.bmsNext(true, 16.0, 54.9, minPackV, 4));

        // Open BMS is an open circuit at kernel level.
        ElectricalKernel k = new ElectricalKernel();
        k.setNodeCount(3);
        k.setOmega(0.0);
        k.setElements(
            List.of(new TestResistor(0.01), batt, new TestResistor(10.0)),
            List.of(new int[]{0, 2}, new int[]{0, 1}, new int[]{1, 2}));
        k.setConductors(List.of());
        k.setElementState(1, new double[]{1.0, 25.0, 1.0});
        assertTrue(loadCurrent(k, 2) < 1e-6, "open BMS isolates the load");
    }

    // ---- solar day/night ----

    @Test
    void solarDayNight() {
        double[] emfCell = {48.0};
        double[] rCell = {0.8};
        double[] irrCell = {1000.0};
        double[] tele = new double[2];
        SolarElement solar = new SolarElement(() -> emfCell[0], () -> rCell[0], () -> irrCell[0], tele);

        assertTrue(SolarElement.isActiveSource(emfCell[0]), "day: EMF > 0 active");
        ElectricalKernel day = new ElectricalKernel();
        day.setNodeCount(2);
        day.setOmega(0.0);
        day.setElements(
            List.of(solar, new TestResistor(10.0)),
            List.of(new int[]{0, 1}, new int[]{0, 1}));
        day.setConductors(List.of());
        day.setElementState(0, SolarElement.newStateArray());
        double dayI = loadCurrent(day, 1);
        assertTrue(dayI > 4.0, "day lamp current=" + dayI);

        emfCell[0] = 0.0;
        assertFalse(SolarElement.isActiveSource(emfCell[0]), "night: EMF = 0 inactive");
        double nightI = loadCurrent(day, 1);
        assertTrue(nightI < 1e-6, "night lamp current=" + nightI);
    }

    // ---- crank: speed decays, lamp current follows EMF ----

    @Test
    void crankSpinDownAndLampFollows() {
        assertEquals(0.85, CrankElement.crankNext(0.5), 1e-12);
        assertEquals(1.0, CrankElement.crankNext(0.9), 1e-12);

        double[] tele = new double[2];
        CrankElement crank = new CrankElement(tele);
        ElectricalKernel k = new ElectricalKernel();
        k.setNodeCount(2);
        k.setOmega(0.0);
        k.setElements(
            List.of(crank, new TestResistor(12.0)),
            List.of(new int[]{0, 1}, new int[]{0, 1}));
        k.setConductors(List.of());
        double[] beState = {1.0, 0.0};
        k.setElementState(0, beState);

        double i0 = loadCurrent(k, 1);
        double expected = 13.8 / (12.0 + 0.15);
        assertEquals(expected, i0, 1e-3);

        for (int t = 0; t < 10; t++) {
            k.tick();
        }
        CrankElement.assignState(beState, k.getElementState(0));
        assertTrue(beState[0] < 0.95 && beState[0] > 0.3, "speed decays, speed=" + beState[0]);
        assertTrue(beState[1] > 0.0, "delivered energy accumulates, E=" + beState[1]);
        assertTrue(tele[CrankElement.TELE_I] > 0.5, "telemetry follows delivery");

        double i1 = loadCurrent(k, 1);
        assertTrue(i1 < i0, "lamp current follows EMF down: " + i0 + " -> " + i1);
        assertEquals(CrankElement.emfForSpeed(beState[0]) / (12.0 + 0.15), i1, 1e-3);
    }

    // ---- terminal conventions ----

    @Test
    void terminalOffsetsAreAdjacentNorthSouth() {
        int[][][] twoTerminal = {
            BatteryElement.TERMINAL_OFFSETS,
            RackElement.TERMINAL_OFFSETS,
            SolarElement.TERMINAL_OFFSETS,
            GeneratorElement.TERMINAL_OFFSETS,
            CrankElement.TERMINAL_OFFSETS,
        };
        for (int[][] offsets : twoTerminal) {
            assertEquals(2, offsets.length);
            // North / south convention shared by all two-terminal source adapters.
            assertArrayEquals(new int[]{0, 0, -1}, offsets[0]);
            assertArrayEquals(new int[]{0, 0, 1}, offsets[1]);
        }
    }
}