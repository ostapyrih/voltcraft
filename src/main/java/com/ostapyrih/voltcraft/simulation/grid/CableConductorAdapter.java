package com.ostapyrih.voltcraft.simulation.grid;

import com.ostapyrih.voltcraft.api.electrical.Conductor;
import com.ostapyrih.voltcraft.api.electrical.GridConstants;
import com.ostapyrih.voltcraft.block.cable.ConductorType;
import net.minecraft.util.math.BlockPos;

/**
 * Kernel {@link Conductor} implementation wrapping one mechanical cable-adjacency branch.
 *
 * <p>Resistance policy (documented choice):</p>
 * <ul>
 *   <li>cable-to-cable and cable-to-terminal branches use {@code cableR}: the
 *       {@link ConductorType#getBaseResistance()} of the known cable endpoint(s)
 *       (average when both endpoints are known cables of different gauges), or
 *       {@link #DEFAULT_CABLE_R_OHM} ({@code 0.001} ohm) when no endpoint carries
 *       a known type (e.g. terminal-side stubs in tests).</li>
 *   <li>terminal-to-terminal branches use {@link #TERMINAL_LINK_R_OHM}
 *       ({@code 0.0001} ohm): an internal near-short, never a melt candidate.</li>
 * </ul>
 *
 * <p>Thermal defaults come from the cable's {@link ConductorType} when known,
 * otherwise copper-ish fallbacks ({@link #DEFAULT_HEAT_CAPACITY_J_PER_K},
 * {@link #DEFAULT_COOLING_COEFF_W_PER_K}, {@link #DEFAULT_MELTING_TEMP_C}).
 * Terminal links use {@link #TERMINAL_LINK_MELTING_TEMP_C} ({@code 1e9}) so
 * {@link ElectricalKernel#findMeltedConductors()} never reports them.</p>
 */
public final class CableConductorAdapter implements Conductor {
    /** Fallback per-adjacency cable resistance when no endpoint type is known. */
    public static final double DEFAULT_CABLE_R_OHM = 0.001;
    /** Internal terminal-to-terminal link resistance (near-short). */
    public static final double TERMINAL_LINK_R_OHM = 0.0001;

    public static final double DEFAULT_HEAT_CAPACITY_J_PER_K = 10.0;
    public static final double DEFAULT_COOLING_COEFF_W_PER_K = 0.2;
    public static final double DEFAULT_MELTING_TEMP_C = 1085.0;
    /** Terminal links never melt; excluded from break candidates by construction. */
    public static final double TERMINAL_LINK_MELTING_TEMP_C = 1.0e9;

    private final int nodeA;
    private final int nodeB;
    private final double resistance;
    private double temperature;
    private final double heatCapacity;
    private final double coolingCoeff;
    private final double meltingTemp;
    private final BlockPos posA;
    private final BlockPos posB;
    private final BlockPos breakCandidate;

    private CableConductorAdapter(
        int nodeA,
        int nodeB,
        double resistance,
        double heatCapacity,
        double coolingCoeff,
        double meltingTemp,
        BlockPos posA,
        BlockPos posB,
        BlockPos breakCandidate
    ) {
        this.nodeA = nodeA;
        this.nodeB = nodeB;
        this.resistance = resistance;
        this.temperature = GridConstants.AMBIENT_C;
        this.heatCapacity = heatCapacity;
        this.coolingCoeff = coolingCoeff;
        this.meltingTemp = meltingTemp;
        this.posA = posA;
        this.posB = posB;
        this.breakCandidate = breakCandidate;
    }

    /** Resolves the per-adjacency cable resistance for a known (possibly null) type. */
    public static double cableResistance(ConductorType type) {
        return type != null ? type.getBaseResistance() : DEFAULT_CABLE_R_OHM;
    }

    /** Cable-involved branch (cable-to-cable or cable-to-terminal). */
    public static CableConductorAdapter cableLink(
        int nodeA,
        int nodeB,
        double resistance,
        ConductorType typeHint,
        BlockPos posA,
        BlockPos posB,
        BlockPos cablePosToBreak
    ) {
        double heatCapacity = typeHint != null ? typeHint.getHeatCapacity() : DEFAULT_HEAT_CAPACITY_J_PER_K;
        double cooling = typeHint != null ? typeHint.getCoolingRate() : DEFAULT_COOLING_COEFF_W_PER_K;
        double melting = typeHint != null ? typeHint.getMeltingTemp() : DEFAULT_MELTING_TEMP_C;
        return new CableConductorAdapter(nodeA, nodeB, resistance, heatCapacity, cooling, melting,
            posA, posB, cablePosToBreak != null ? cablePosToBreak.toImmutable() : posA.toImmutable());
    }

    /** Terminal-to-terminal internal link; never melts, never a break candidate. */
    public static CableConductorAdapter terminalLink(int nodeA, int nodeB, BlockPos posA, BlockPos posB) {
        return new CableConductorAdapter(nodeA, nodeB, TERMINAL_LINK_R_OHM,
            DEFAULT_HEAT_CAPACITY_J_PER_K, DEFAULT_COOLING_COEFF_W_PER_K, TERMINAL_LINK_MELTING_TEMP_C,
            posA, posB, null);
    }

    @Override
    public int nodeA() {
        return nodeA;
    }

    @Override
    public int nodeB() {
        return nodeB;
    }

    @Override
    public double resistance() {
        return resistance;
    }

    @Override
    public double temperature() {
        return temperature;
    }

    @Override
    public void setTemperature(double celsius) {
        this.temperature = celsius;
    }

    @Override
    public double heatCapacity() {
        return heatCapacity;
    }

    @Override
    public double coolingCoeff() {
        return coolingCoeff;
    }

    @Override
    public double meltingTemp() {
        return meltingTemp;
    }

    public BlockPos posA() {
        return posA;
    }

    public BlockPos posB() {
        return posB;
    }

    /** World position to queue for breaking when this conductor melts; null for terminal links. */
    public BlockPos breakCandidate() {
        return breakCandidate;
    }

    /** True for internal terminal links, which can never melt by construction. */
    public boolean isTerminalLink() {
        return breakCandidate == null;
    }
}
