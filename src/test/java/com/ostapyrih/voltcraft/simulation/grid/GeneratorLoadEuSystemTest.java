package com.ostapyrih.voltcraft.simulation.grid;

import com.ostapyrih.voltcraft.api.electrical.ElectricalElement;
import com.ostapyrih.voltcraft.api.electrical.GridConstants;
import com.ostapyrih.voltcraft.api.grid.KernelAttachedBlock;
import com.ostapyrih.voltcraft.block.cable.ConductorType;
import com.ostapyrih.voltcraft.simulation.conversion.EuConverterLogic;
import com.ostapyrih.voltcraft.simulation.electrical.ConverterElement;
import com.ostapyrih.voltcraft.simulation.electrical.CreativeLoadElement;
import com.ostapyrih.voltcraft.simulation.electrical.GeneratorElement;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Observational integration suite: portable fuel generator (230V 50Hz) feeding
 * a creative load directly, an EU converter alone, and both in parallel — plus
 * cable break/place churn, fuel exhaustion, and block hot-swap.
 *
 * <p>No production code is modified here and no behavior is tuned: every model
 * below mirrors its production block entity 1:1 —
 * {@code PortableGeneratorBlockEntity} (Thevenin 230V/0.15ohm while fuel remains,
 * AC source), {@code CreativeLoadBlockEntity} (resistive {@code Vnom^2/P} stamp
 * on AC islands, constant-power on DC), {@code EuConverterBlockEntity}
 * (4-terminal {@code ConverterElement} with reserved-open output pair and staged
 * input demand). Each test prints per-tick telemetry so the real steady-state,
 * brownout, and recovery behavior can be read directly from the output.
 */
public class GeneratorLoadEuSystemTest {

    static BlockPos pos(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    /** Mock fuel generator mirroring PortableGeneratorBlockEntity logic. */
    static class GenModel implements KernelAttachedBlock {
        final BlockPos pos;
        final Direction facing;
        final double[] telemetry = new double[2];
        final double[] stateArray;
        double totalEnergyJoules = 0.0;
        final GeneratorElement element;

        GenModel(BlockPos pos, Direction facing, double fuelTicks) {
            this.pos = pos;
            this.facing = facing;
            this.stateArray = new double[]{GridConstants.AMBIENT_C, fuelTicks};
            this.element = new GeneratorElement(this::isRunning, telemetry);
        }

        boolean isRunning() {
            return stateArray[GeneratorElement.STATE_FUEL] > 0.0;
        }

        @Override
        public BlockPos getPos() { return pos; }

        @Override
        public BlockPos[] getTerminalPositions() {
            // [FRONT (-), BACK (+)]
            return GeneratorElement.resolveTerminals(pos, facing);
        }

        @Override
        public ElectricalElement getElement() { return element; }

        @Override
        public double[] getStateArray() { return stateArray.clone(); }

        @Override
        public void setStateArray(double[] s) { System.arraycopy(s, 0, stateArray, 0, s.length); }

        @Override
        public void tickElectrical(ServerWorld world) {
            // Mirrors PortableGeneratorBlockEntity.java tickElectrical (minus blockstate sync).
            if (!isRunning()) {
                telemetry[GeneratorElement.TELE_I] = 0.0;
                telemetry[GeneratorElement.TELE_P] = 0.0;
            } else {
                double p = telemetry[GeneratorElement.TELE_P];
                if (p > 0.0) {
                    totalEnergyJoules += p * GridConstants.DT;
                }
            }
        }

        @Override
        public boolean isActiveSource() { return GeneratorElement.isActiveSource(isRunning()); }

        @Override
        public boolean isACSource() { return true; }

        double getDeliveredPower() { return telemetry[GeneratorElement.TELE_P]; }
        double getDeliveredCurrent() { return telemetry[GeneratorElement.TELE_I]; }
        double getFuel() { return stateArray[GeneratorElement.STATE_FUEL]; }
    }

    /** Mock load mirroring CreativeLoadBlockEntity logic (230V nominal for AC tests). */
    static class LoadModel implements KernelAttachedBlock {
        final BlockPos pos;
        final Direction facing;
        final double[] telemetry = new double[2];
        boolean enabled = true;
        double targetWatts = 500.0;
        final CreativeLoadElement element;

        LoadModel(BlockPos pos, Direction facing, double watts) {
            this.pos = pos;
            this.facing = facing;
            this.targetWatts = watts;
            this.element = new CreativeLoadElement(
                () -> CreativeLoadElement.MODE_POWER,
                () -> targetWatts,
                () -> enabled,
                () -> 230.0,
                telemetry
            );
        }

        @Override
        public BlockPos getPos() { return pos; }

        @Override
        public BlockPos[] getTerminalPositions() {
            return CreativeLoadElement.resolveTerminals(pos, facing);
        }

        @Override
        public ElectricalElement getElement() { return element; }

        @Override
        public double[] getStateArray() { return new double[0]; }

        @Override
        public void setStateArray(double[] s) {}

        @Override
        public void tickElectrical(ServerWorld world) {}

        @Override
        public boolean isActiveSource() { return false; }

        @Override
        public boolean isACSource() { return false; }

        double getPowerDrawn() {
            return telemetry[CreativeLoadElement.TELE_V] * telemetry[CreativeLoadElement.TELE_I];
        }

        double getTerminalVoltage() { return telemetry[CreativeLoadElement.TELE_V]; }
    }

    /**
     * Mock EU bridge mirroring EuConverterBlockEntity logic: 4-terminal converter
     * with reserved-open output pair (EMF 0) and staged input demand. Production
     * never feeds terminal measurements into {@link EuConverterLogic} from the
     * electrical phase (no {@code onPowerReceived} call) and only refreshes demand
     * from the server tick, so the demand observed here stays at its natural value.
     */
    static class EuModel implements KernelAttachedBlock {
        final BlockPos pos;
        final Direction facing;
        final double[] telemetry = new double[ConverterElement.TELE_LEN];
        final double[] stateArray = ConverterElement.newStateArray();
        final EuConverterLogic logic;
        double stagedInputDemandWatts = 0.0;
        final ConverterElement element;

        EuModel(BlockPos pos, Direction facing) {
            this.pos = pos;
            this.facing = facing;
            this.logic = new EuConverterLogic(pos);
            this.element = new ConverterElement(
                logic::isTripped,
                () -> stagedInputDemandWatts,
                () -> 0.0,
                () -> EuConverterLogic.NOMINAL_VOLTAGE,
                telemetry
            );
        }

        @Override
        public BlockPos getPos() { return pos; }

        @Override
        public BlockPos[] getTerminalPositions() {
            // [BACK+, LEFT-, FRONT+, RIGHT-] — input pair first, like production.
            return ConverterElement.resolveConverterTerminals(pos, facing);
        }

        @Override
        public ElectricalElement getElement() { return element; }

        @Override
        public double[] getStateArray() { return stateArray.clone(); }

        @Override
        public void setStateArray(double[] s) { System.arraycopy(s, 0, stateArray, 0, s.length); }

        @Override
        public void tickElectrical(ServerWorld world) {
            // Exactly EuConverterBlockEntity.java tickElectrical: stage-only, no
            // measurement feedback into the logic from the electrical phase.
            double teleVIn = telemetry[ConverterElement.TELE_V_IN];
            double base = logic.getNominalPowerDemand();
            if (!Double.isFinite(base) || base < 0.0) {
                base = 0.0;
            }
            this.stagedInputDemandWatts = (logic.isTripped()
                || !(teleVIn > ConverterElement.DEAD_RAIL_VOLTS))
                ? 0.0 : base;
        }

        @Override
        public boolean isActiveSource() { return false; }

        @Override
        public boolean isACSource() { return false; }

        double getInputVoltage() { return telemetry[ConverterElement.TELE_V_IN]; }
    }

    // ------------------------------------------------------------------
    // Wiring helpers (y = 64 plane)
    // ------------------------------------------------------------------

    /** Compact direct circuit: generator (0,64,0) <-> load (2,64,0) via short rails. */
    static void connectDirectGenLoad(GridManager m, int loadX) {
        for (int x = 0; x <= loadX; x++) {
            m.putCable(pos(x, 64, -1), ConductorType.INSULATED_COPPER);
            m.putCable(pos(x, 64, 1), ConductorType.INSULATED_COPPER);
        }
    }

    /**
     * West-side direct rails: generator (0,64,0) <-> load (-2,64,0). Used together
     * with the EU plant, whose minus-route vertical runs at x=+3 — east-side rails
     * would join it into a near-short loop across the generator, so the direct
     * branch goes west where no EU-plant cable can close a loop through it.
     */
    static void connectDirectGenLoadWest(GridManager m) {
        for (int x = -2; x <= 0; x++) {
            m.putCable(pos(x, 64, -1), ConductorType.INSULATED_COPPER);
            m.putCable(pos(x, 64, 1), ConductorType.INSULATED_COPPER);
        }
    }

    /**
     * Full plant mirroring the solar/MPPT layout: generator (0,64,0) NORTH feeds
     * EU input at (0,64,6) SOUTH; EU output bus runs to the load row z=13.
     * Generator (-) at (0,64,-1), (+) at (0,64,1); EU In+ (0,64,5), In- (1,64,6),
     * Out+ (0,64,7), Out- (-1,64,6); load row z=11/13.
     */
    static void connectEuPlant(GridManager m) {
        // Generator (+) -> EU In(+): (0,64,1) through (0,64,5).
        for (int z = 1; z <= 5; z++) {
            m.putCable(pos(0, 64, z), ConductorType.INSULATED_COPPER);
        }
        // Generator (-) -> EU In(-): (0,64,-1) -> (3,64,-1) -> (3,64,6) -> (1,64,6).
        for (int x = 0; x <= 3; x++) {
            m.putCable(pos(x, 64, -1), ConductorType.INSULATED_COPPER);
        }
        for (int z = -1; z <= 6; z++) {
            m.putCable(pos(3, 64, z), ConductorType.INSULATED_COPPER);
        }
        for (int x = 1; x <= 3; x++) {
            m.putCable(pos(x, 64, 6), ConductorType.INSULATED_COPPER);
        }
        // EU Out(-) -> load row (-): (-1,64,6) -> (-2,64,6..11) -> (-2..4,64,11).
        m.putCable(pos(-1, 64, 6), ConductorType.HEAVY_COPPER);
        for (int z = 6; z <= 11; z++) {
            m.putCable(pos(-2, 64, z), ConductorType.HEAVY_COPPER);
        }
        for (int x = -2; x <= 4; x++) {
            m.putCable(pos(x, 64, 11), ConductorType.HEAVY_COPPER);
        }
        // EU Out(+) -> load row (+): (0,64,7) -> (0..6,64,8) -> (6,64,8..13) -> (0..6,64,13).
        m.putCable(pos(0, 64, 7), ConductorType.HEAVY_COPPER);
        for (int x = 0; x <= 6; x++) {
            m.putCable(pos(x, 64, 8), ConductorType.HEAVY_COPPER);
        }
        for (int z = 8; z <= 13; z++) {
            m.putCable(pos(6, 64, z), ConductorType.HEAVY_COPPER);
        }
        for (int x = 0; x <= 6; x++) {
            m.putCable(pos(x, 64, 13), ConductorType.HEAVY_COPPER);
        }
    }

    static void stepTicks(GridManager m, int count) {
        for (int i = 0; i < count; i++) {
            m.tick(null);
        }
    }

    static void assertAllFinite(String tag, double... values) {
        for (double v : values) {
            assertTrue(Double.isFinite(v), tag + ": telemetry must stay finite, got " + v);
        }
    }

    static void assertKernelFinite(GridManager m, String tag) {
        for (IslandContext island : m.getIslands()) {
            var v = island.kernel().getLastSolution();
            if (v == null) {
                continue;
            }
            for (var c : v) {
                assertTrue(c.isFinite(), tag + ": kernel voltage must stay finite, got " + c);
            }
        }
    }

    static String islandsOf(GridManager m) {
        StringBuilder sb = new StringBuilder("[");
        for (IslandContext island : m.getIslands()) {
            sb.append("{nodes=").append(island.nodeCount())
                .append(",blocks=").append(island.blocks().size())
                .append(",omega=").append(island.omega()).append("} ");
        }
        return sb.append("]").toString();
    }

    // ------------------------------------------------------------------
    // 1. Generator -> single load
    // ------------------------------------------------------------------

    @Test
    @DisplayName("1. Generator feeds a single 500W load directly")
    void genDirectSingleLoad() {
        GridManager m = new GridManager();
        GenModel gen = new GenModel(pos(0, 64, 0), Direction.NORTH, 1.0e6);
        LoadModel load = new LoadModel(pos(2, 64, 0), Direction.NORTH, 500.0);
        m.putAttachedBlock(gen);
        m.putAttachedBlock(load);
        connectDirectGenLoad(m, 2);

        for (int t = 1; t <= 8; t++) {
            m.tick(null);
            System.out.println("T1 tick " + t + ": GenP=" + gen.getDeliveredPower()
                + " GenI=" + gen.getDeliveredCurrent() + " running=" + gen.isRunning()
                + " LoadP=" + load.getPowerDrawn() + " LoadV=" + load.getTerminalVoltage()
                + " islands=" + islandsOf(m));
        }
        assertAllFinite("T1", gen.getDeliveredPower(), gen.getDeliveredCurrent(),
            load.getPowerDrawn(), load.getTerminalVoltage());
        assertKernelFinite(m, "T1");
        // AC island: resistive load R = 230^2/500 = 105.8 ohm on a 230V/0.15 source.
        assertTrue(load.getPowerDrawn() > 450.0 && load.getPowerDrawn() < 550.0,
            "500W load should draw ~500W from the generator, got " + load.getPowerDrawn());
        assertTrue(load.getTerminalVoltage() > 200.0 && load.getTerminalVoltage() < 245.0,
            "Load terminal voltage should sit near 230V, got " + load.getTerminalVoltage());
    }

    // ------------------------------------------------------------------
    // 2. Generator -> two parallel loads
    // ------------------------------------------------------------------

    @Test
    @DisplayName("2. Generator feeds 500W + 1000W loads in parallel")
    void genDirectTwoLoadsParallel() {
        GridManager m = new GridManager();
        GenModel gen = new GenModel(pos(0, 64, 0), Direction.NORTH, 1.0e6);
        LoadModel loadA = new LoadModel(pos(2, 64, 0), Direction.NORTH, 500.0);
        LoadModel loadB = new LoadModel(pos(4, 64, 0), Direction.NORTH, 1000.0);
        m.putAttachedBlock(gen);
        m.putAttachedBlock(loadA);
        m.putAttachedBlock(loadB);
        connectDirectGenLoad(m, 4);

        for (int t = 1; t <= 8; t++) {
            m.tick(null);
            System.out.println("T2 tick " + t + ": GenP=" + gen.getDeliveredPower()
                + " LoadA=" + loadA.getPowerDrawn() + " LoadB=" + loadB.getPowerDrawn()
                + " islands=" + islandsOf(m));
        }
        assertAllFinite("T2", gen.getDeliveredPower(), loadA.getPowerDrawn(), loadB.getPowerDrawn());
        assertKernelFinite(m, "T2");
        assertTrue(loadA.getPowerDrawn() > 400.0,
            "500W branch should stay powered in parallel, got " + loadA.getPowerDrawn());
        assertTrue(loadB.getPowerDrawn() > 800.0,
            "1000W branch should stay powered in parallel, got " + loadB.getPowerDrawn());
        assertTrue(gen.getDeliveredPower() < 1800.0,
            "Total must stay within the 1800W rating, got " + gen.getDeliveredPower());
    }

    // ------------------------------------------------------------------
    // 3. Generator -> EU converter alone (load behind the reserved-open output)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("3. Generator into EU converter; load sits behind the open EU output pair")
    void genIntoEuConverterAlone() {
        GridManager m = new GridManager();
        GenModel gen = new GenModel(pos(0, 64, 0), Direction.NORTH, 1.0e6);
        EuModel eu = new EuModel(pos(0, 64, 6), Direction.SOUTH);
        LoadModel load = new LoadModel(pos(4, 64, 12), Direction.NORTH, 500.0);
        m.putAttachedBlock(gen);
        m.putAttachedBlock(eu);
        m.putAttachedBlock(load);
        connectEuPlant(m);

        for (int t = 1; t <= 8; t++) {
            m.tick(null);
            System.out.println("T3 tick " + t + ": GenP=" + gen.getDeliveredPower()
                + " EuDemand=" + eu.stagedInputDemandWatts + " EuVin=" + eu.getInputVoltage()
                + " EuTripped=" + eu.logic.isTripped() + " EuStored=" + eu.logic.getStoredEu()
                + " LoadP=" + load.getPowerDrawn() + " islands=" + islandsOf(m));
        }
        assertAllFinite("T3", gen.getDeliveredPower(), load.getPowerDrawn(), eu.getInputVoltage());
        assertKernelFinite(m, "T3");
        // Production wiring never feeds measurements into EuConverterLogic, so its
        // demand stays 0W and the reserved-open output pair leaves the load dark.
        assertEquals(0.0, eu.stagedInputDemandWatts, 1e-9,
            "EU demand stays 0W under production wiring, got " + eu.stagedInputDemandWatts);
        assertTrue(load.getPowerDrawn() < 1.0,
            "Load behind the open EU output pair must stay dark, got " + load.getPowerDrawn());
        assertFalse(eu.logic.isTripped(), "EU bridge must not trip on a healthy 230V feed");
    }

    // ------------------------------------------------------------------
    // 4. Generator -> load AND EU converter at once (parallel branches)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("4. Generator feeds a direct 500W load and the EU converter in parallel")
    void genToLoadAndEuInParallel() {
        GridManager m = new GridManager();
        GenModel gen = new GenModel(pos(0, 64, 0), Direction.NORTH, 1.0e6);
        EuModel eu = new EuModel(pos(0, 64, 6), Direction.SOUTH);
        LoadModel busLoad = new LoadModel(pos(-2, 64, 0), Direction.NORTH, 500.0);
        LoadModel euLoad = new LoadModel(pos(4, 64, 12), Direction.NORTH, 500.0);
        m.putAttachedBlock(gen);
        m.putAttachedBlock(eu);
        m.putAttachedBlock(busLoad);
        m.putAttachedBlock(euLoad);
        connectDirectGenLoadWest(m);
        connectEuPlant(m);

        for (int t = 1; t <= 8; t++) {
            m.tick(null);
            System.out.println("T4 tick " + t + ": GenP=" + gen.getDeliveredPower()
                + " BusLoad=" + busLoad.getPowerDrawn()
                + " EuDemand=" + eu.stagedInputDemandWatts
                + " EuLoad=" + euLoad.getPowerDrawn() + " islands=" + islandsOf(m));
        }
        assertAllFinite("T4", gen.getDeliveredPower(), busLoad.getPowerDrawn(), euLoad.getPowerDrawn());
        assertKernelFinite(m, "T4");
        assertTrue(busLoad.getPowerDrawn() > 400.0,
            "Direct branch must stay powered next to the EU bridge, got " + busLoad.getPowerDrawn());
        assertTrue(gen.getDeliveredPower() < 1800.0,
            "Generator must stay within its 1800W rating, got " + gen.getDeliveredPower());
        assertTrue(euLoad.getPowerDrawn() < 1.0,
            "Branch behind the EU output must stay dark, got " + euLoad.getPowerDrawn());
    }

    // ------------------------------------------------------------------
    // 5. Generator feed cable break / reconnect under load
    // ------------------------------------------------------------------

    @Test
    @DisplayName("5. Direct feed: break generator (+) then (-) under 500W, reconnect each")
    void genFeedBreakAndReconnect() {
        GridManager m = new GridManager();
        GenModel gen = new GenModel(pos(0, 64, 0), Direction.NORTH, 1.0e6);
        LoadModel load = new LoadModel(pos(2, 64, 0), Direction.NORTH, 500.0);
        m.putAttachedBlock(gen);
        m.putAttachedBlock(load);
        connectDirectGenLoad(m, 2);
        stepTicks(m, 5);
        assertTrue(load.getPowerDrawn() > 400.0, "Precondition: load powered");

        // Break (+) feed cable at (1,64,1).
        m.removeCable(pos(1, 64, 1));
        stepTicks(m, 5);
        System.out.println("T5 plus-cut: GenP=" + gen.getDeliveredPower()
            + " LoadP=" + load.getPowerDrawn() + " islands=" + islandsOf(m));
        assertAllFinite("T5-plus", gen.getDeliveredPower(), load.getPowerDrawn());
        assertKernelFinite(m, "T5-plus");

        // Reconnect (+), then break (-) feed cable at (1,64,-1).
        m.putCable(pos(1, 64, 1), ConductorType.INSULATED_COPPER);
        stepTicks(m, 5);
        System.out.println("T5 plus-restored: LoadP=" + load.getPowerDrawn());
        assertTrue(load.getPowerDrawn() > 400.0,
            "Load must resume after (+) reconnect, got " + load.getPowerDrawn());

        m.removeCable(pos(1, 64, -1));
        stepTicks(m, 5);
        System.out.println("T5 minus-cut: GenP=" + gen.getDeliveredPower()
            + " LoadP=" + load.getPowerDrawn() + " islands=" + islandsOf(m));
        assertAllFinite("T5-minus", gen.getDeliveredPower(), load.getPowerDrawn());
        assertKernelFinite(m, "T5-minus");

        m.putCable(pos(1, 64, -1), ConductorType.INSULATED_COPPER);
        stepTicks(m, 5);
        System.out.println("T5 minus-restored: LoadP=" + load.getPowerDrawn());
        assertTrue(load.getPowerDrawn() > 400.0,
            "Load must resume after (-) reconnect, got " + load.getPowerDrawn());
    }

    // ------------------------------------------------------------------
    // 6. Fuel exhaustion under load
    // ------------------------------------------------------------------

    @Test
    @DisplayName("6. Generator runs dry under 500W load")
    void genFuelExhaustion() {
        GridManager m = new GridManager();
        GenModel gen = new GenModel(pos(0, 64, 0), Direction.NORTH, 25.0);
        LoadModel load = new LoadModel(pos(2, 64, 0), Direction.NORTH, 500.0);
        m.putAttachedBlock(gen);
        m.putAttachedBlock(load);
        connectDirectGenLoad(m, 2);

        for (int t = 1; t <= 120; t++) {
            m.tick(null);
            if (t % 10 == 0 || !gen.isRunning()) {
                System.out.println("T6 tick " + t + ": fuel=" + gen.getFuel()
                    + " running=" + gen.isRunning() + " GenP=" + gen.getDeliveredPower()
                    + " LoadP=" + load.getPowerDrawn() + " islands=" + islandsOf(m));
            }
            if (!gen.isRunning() && t > 30) {
                stepTicks(m, 5);
                System.out.println("T6 dry: LoadP=" + load.getPowerDrawn());
                break;
            }
        }
        assertAllFinite("T6", gen.getDeliveredPower(), load.getPowerDrawn(), gen.getFuel());
        assertKernelFinite(m, "T6");
        assertFalse(gen.isRunning(), "Tank seeded with 25 fuel ticks must run dry");
        assertTrue(load.getPowerDrawn() < 5.0,
            "Load must go dark on a dry tank, got " + load.getPowerDrawn());
    }

    // ------------------------------------------------------------------
    // 7. Generator hot-swap under load
    // ------------------------------------------------------------------

    @Test
    @DisplayName("7. Hot-swap generator block while 500W load is running")
    void genHotSwapUnderLoad() {
        GridManager m = new GridManager();
        GenModel gen = new GenModel(pos(0, 64, 0), Direction.NORTH, 1.0e6);
        LoadModel load = new LoadModel(pos(2, 64, 0), Direction.NORTH, 500.0);
        m.putAttachedBlock(gen);
        m.putAttachedBlock(load);
        connectDirectGenLoad(m, 2);
        stepTicks(m, 5);
        assertTrue(load.getPowerDrawn() > 400.0, "Precondition: load powered");

        m.removeAttachedBlock(pos(0, 64, 0));
        stepTicks(m, 5);
        System.out.println("T7 removed: GenP(stale)=" + gen.getDeliveredPower()
            + " LoadP=" + load.getPowerDrawn() + " islands=" + islandsOf(m));
        assertAllFinite("T7-removed", load.getPowerDrawn());
        assertKernelFinite(m, "T7-removed");

        gen = new GenModel(pos(0, 64, 0), Direction.NORTH, 1.0e6);
        m.putAttachedBlock(gen);
        stepTicks(m, 5);
        System.out.println("T7 replaced: LoadP=" + load.getPowerDrawn()
            + " GenP=" + gen.getDeliveredPower());
        assertTrue(load.getPowerDrawn() > 400.0,
            "Load must resume after generator replacement, got " + load.getPowerDrawn());
    }

    // ------------------------------------------------------------------
    // 8. Sequential churn across the EU plant under load
    // ------------------------------------------------------------------

    @Test
    @DisplayName("8. Sequential cable churn across generator/EU/load run under 500W")
    void euPlantSequentialChurn() {
        GridManager m = new GridManager();
        GenModel gen = new GenModel(pos(0, 64, 0), Direction.NORTH, 1.0e6);
        EuModel eu = new EuModel(pos(0, 64, 6), Direction.SOUTH);
        LoadModel busLoad = new LoadModel(pos(-2, 64, 0), Direction.NORTH, 500.0);
        LoadModel euLoad = new LoadModel(pos(4, 64, 12), Direction.NORTH, 500.0);
        m.putAttachedBlock(gen);
        m.putAttachedBlock(eu);
        m.putAttachedBlock(busLoad);
        m.putAttachedBlock(euLoad);
        connectDirectGenLoadWest(m);
        connectEuPlant(m);
        stepTicks(m, 5);
        assertTrue(busLoad.getPowerDrawn() > 400.0, "Precondition: direct branch powered");

        BlockPos[] cuts = {
            pos(0, 64, 3), // generator (+) feed to EU input
            pos(2, 64, 6), // generator (-) feed to EU input
            pos(0, 64, 7), // EU Out(+) tap
            pos(-1, 64, 1), // direct (+) rail mid (west branch)
        };
        ConductorType[] types = {
            ConductorType.INSULATED_COPPER,
            ConductorType.INSULATED_COPPER,
            ConductorType.HEAVY_COPPER,
            ConductorType.INSULATED_COPPER,
        };
        for (int stage = 0; stage < cuts.length; stage++) {
            m.removeCable(cuts[stage]);
            stepTicks(m, 5);
            System.out.println("T8 cut " + cuts[stage] + ": GenP=" + gen.getDeliveredPower()
                + " BusLoad=" + busLoad.getPowerDrawn()
                + " EuDemand=" + eu.stagedInputDemandWatts
                + " EuLoad=" + euLoad.getPowerDrawn() + " islands=" + islandsOf(m));
            assertAllFinite("T8-cut" + stage, gen.getDeliveredPower(),
                busLoad.getPowerDrawn(), euLoad.getPowerDrawn());
            assertKernelFinite(m, "T8-cut" + stage);

            m.putCable(cuts[stage], types[stage]);
            stepTicks(m, 5);
            System.out.println("T8 restored " + cuts[stage] + ": BusLoad=" + busLoad.getPowerDrawn());
            assertTrue(busLoad.getPowerDrawn() > 400.0,
                "Direct branch must recover after restoring " + cuts[stage]
                    + ", got " + busLoad.getPowerDrawn());
        }
    }
}
