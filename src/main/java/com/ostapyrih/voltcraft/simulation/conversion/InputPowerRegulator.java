package com.ostapyrih.voltcraft.simulation.conversion;

/**
 * Input-current servo for converters fed by weak sources (small solar strings,
 * nearly-depleted batteries). Behaves like real converter front-ends: instead of
 * bang-bang cutting the output when the rail sags, it trims how much current the
 * converter allows itself to draw until the rail holds steady — a 40W panel then
 * delivers a steady ~40W rather than strobing between 0 and full.
 *
 * <p>Two properties make this converge instead of flicker:
 * <ul>
 *   <li>Linear proportional control toward the servo target with deliberately
 *       small ±5% authority per tick: converges geometrically near the setpoint
 *       yet can never slam the rail in a single step. Deep collapse keeps a
 *       hard backoff plus a sensing probe so the rail is re-tested every tick.</li>
 *   <li>The cap is a <em>current</em>, so while it binds the grid sees a
 *       constant-current load ({@code R = V/I}). That map is contractive at every
 *       operating point, unlike constant-power stamping ({@code R = V^2/P}),
 *       whose discrete update 2-cycles once the load nears the source maximum.</li>
 * </ul>
 */
public final class InputPowerRegulator {

    // --- updateCurrentCap tuning ---
    private static final double MIN_CEILING_AMPS = 0.05;
    private static final double DEAD_RAIL_VOLTAGE = 1.0;
    private static final double COLLAPSE_VIN_MARGIN = 0.95;
    private static final double COLLAPSE_BACKOFF_FACTOR = 0.85;
    private static final double SENSE_PROBE_AMPS = 0.15;
    private static final double PROPORTIONAL_FLOOR = 0.50;
    private static final double PROPORTIONAL_CEILING = 1.05;
    private static final double MIN_TARGET_VOLTAGE = 0.1;

    // --- isOutputHungry tuning ---
    private static final double HUNGRY_MIN_EMF = 0.5;
    private static final double HUNGRY_SAG_MARGIN = 0.90;

    // --- slewDemandUp tuning ---
    private static final double SLEW_GROWTH_FACTOR = 1.1;
    private static final double SLEW_SEED_WATTS = 1.0;

    private InputPowerRegulator() {}

    /**
     * @param capAmps       current input-current cap in Amps (0 = draw nothing)
     * @param capMaxAmps    rated ceiling in Amps (full-load input current)
     * @param inputVoltage  measured rail voltage in Volts
     * @param minVin        converter minimum input voltage in Volts
     * @param targetVoltage rail voltage to park at in Volts ({@code >= minVin});
     *                      MPPT tracking voltage for solar-fed converters,
     *                      turn-on threshold otherwise
     * @return updated input-current cap in Amps
     */
    public static double updateCurrentCap(
        double capAmps,
        double capMaxAmps,
        double inputVoltage,
        double minVin,
        double targetVoltage
    ) {
        double ceiling = Math.max(MIN_CEILING_AMPS, capMaxAmps);
        if (inputVoltage <= DEAD_RAIL_VOLTAGE) {
            return 0.0;
        }

        double cap = clamp(capAmps, 0.0, ceiling);

        if (inputVoltage < minVin * COLLAPSE_VIN_MARGIN) {
            // Collapse safety: back off fast but keep a sensing probe so the rail
            // is re-tested every tick and can always recover. Still bounded by the
            // converter's own ceiling — a weak converter's probe must not exceed
            // its rated current.
            double backedOff = Math.max(SENSE_PROBE_AMPS, cap * COLLAPSE_BACKOFF_FACTOR);
            return Math.min(ceiling, backedOff);
        }

        // Guard against a degenerate zero target (should not happen given the
        // documented targetVoltage >= minVin contract, but a div-by-zero here
        // would be an ugly way to find out otherwise).
        double target = Math.max(MIN_TARGET_VOLTAGE, Math.max(minVin, targetVoltage));

        double f = proportionalFactor(inputVoltage, target);
        double out = cap * f;
        if (out < SENSE_PROBE_AMPS && inputVoltage > target) {
            out = SENSE_PROBE_AMPS; // re-probe from zero on a live rail (soft-start kick)
        }
        return clamp(out, 0.0, ceiling);
    }

    /**
     * Linear proportional control toward the target: gentle ±5% authority near
     * the setpoint for precise parking, deepening to -50% far below it so a
     * collapsing rail sheds load faster than the collapse itself feeds.
     */
    private static double proportionalFactor(double inputVoltage, double target) {
        double f = 1.0 + (inputVoltage - target) / target;
        return clamp(f, PROPORTIONAL_FLOOR, PROPORTIONAL_CEILING);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Downstream-hunger detector: reports whether the output terminal sags below
     * the regulated EMF, meaning the output side demonstrably wants more power
     * than it gets. Real CV loops see this as terminal sag and answer by drawing
     * more input; without it the input has no way to know a newly-connected (or
     * growing) load is starving, because delivered output power alone cannot
     * distinguish "load satisfied" from "clamped".
     *
     * <p>Deliberately a fixed relative threshold, never a delivery-vs-ceiling
     * comparison: that threshold moves with the input draw it drives (the ceiling
     * itself derives from last tick's input), which 2-cycles in every steady
     * state above a fraction of rating. Terminal voltage is monotonic in input
     * within a tick, so this detector converges.
     *
     * @param emf        regulated output EMF in Volts
     * @param hasGrid    true if an output grid is attached to read a terminal from
     * @param nodeVoltage measured output terminal voltage in Volts (ignored without a grid)
     * @return true when a gridded terminal sags below 90% of EMF
     */
    public static boolean isOutputHungry(double emf, boolean hasGrid, double nodeVoltage) {
        if (emf <= HUNGRY_MIN_EMF || !hasGrid) {
            return false;
        }
        // Wide 10% margin: current-limit forcing sags nodes far deeper than this,
        // while normal ripple stays inside it — a tight margin flaps on the
        // boundary it drives.
        return nodeVoltage < emf * HUNGRY_SAG_MARGIN;
    }

    /**
     * Rate-limits demand growth while hungry: at most +10% plus 1W per tick under
     * the servo ceiling. Lets a starved output ramp onto newly available input
     * without slamming the rail past the source knee in a single step (a coarse
     * slew always wins the race against the servo's recovery, ending in
     * collapse-and-reset). Small additive seed keeps cold start moving from zero.
     *
     * @param lastWatts  demand served last tick in Watts (negative = uninitialized)
     * @param servoWatts servo-allowed draw in Watts
     * @return demand to serve this tick in Watts
     */
    public static double slewDemandUp(double lastWatts, double servoWatts) {
        double prev = Math.max(0.0, lastWatts);
        double allowedGrowth = prev * SLEW_GROWTH_FACTOR + SLEW_SEED_WATTS;
        return Math.min(Math.max(0.0, servoWatts), allowedGrowth);
    }
}