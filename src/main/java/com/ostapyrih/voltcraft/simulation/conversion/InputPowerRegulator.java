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
        double ceiling = Math.max(0.05, capMaxAmps);
        if (inputVoltage <= 1.0) {
            return 0.0;
        }
        double target = Math.max(minVin, targetVoltage);
        double cap = Math.min(Math.max(0.0, capAmps), ceiling);
        if (inputVoltage < minVin * 0.95) {
            // Collapse safety: back off fast but keep a 0.15A sensing probe so
            // the rail is re-tested every tick and can always recover.
            return Math.max(0.15, cap * 0.85);
        }
        // Linear proportional control toward the target: gentle ±5% authority near
        // the setpoint for precise parking, deepening to -50% far below it so a
        // collapsing rail sheds load faster than the collapse itself feeds.
        double f = 1.0 + (inputVoltage - target) / target;
        if (f < 0.50) {
            f = 0.50;
        }
        if (f > 1.05) {
            f = 1.05;
        }
        double out = cap * f;
        if (out < 0.15 && inputVoltage > target) {
            out = 0.15; // re-probe from zero on a live rail (soft-start kick)
        }
        return Math.min(ceiling, Math.max(0.0, out));
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
        if (emf <= 0.5 || !hasGrid) {
            return false;
        }
        // Wide 10% margin: current-limit forcing sags nodes far deeper than this,
        // while normal ripple stays inside it — a tight margin flaps on the
        // boundary it drives.
        return nodeVoltage < emf * 0.90;
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
        return Math.min(Math.max(0.0, servoWatts), prev * 1.1 + 1.0);
    }
}
