package com.ostapyrih.voltcraft.simulation.grid;

import com.ostapyrih.voltcraft.api.electrical.Complex;
import com.ostapyrih.voltcraft.api.electrical.Conductor;
import com.ostapyrih.voltcraft.api.electrical.ElectricalElement;
import com.ostapyrih.voltcraft.api.electrical.GridConstants;
import com.ostapyrih.voltcraft.api.electrical.Stamps;
import com.ostapyrih.voltcraft.simulation.solver.ComplexNodalSolver;
import com.ostapyrih.voltcraft.simulation.solver.ComplexNodalSolver.SolveResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Central nodal-analysis kernel. Owns all element state and performs the full
 * Newton solve. Elements and conductors are lightweight topological descriptors;
 * all voltages, states, and iteration live here.
 *
 * <p>This class is final and not intended for subclassing.</p>
 *
 * <p>Reference formulation: each galvanically-connected component has exactly
 * one reference node (V = 0). The component containing node {@code 0} uses
 * node {@code 0} as its reference; any other component (e.g., a galvanically
 * isolated converter output pair) uses its lowest-index node. No shunt is added
 * to any diagonal. After element and conductor stamps, {@link #buildSystem}
 * forces each component reference row to a unit row and zeroes its injection;
 * columns of reference nodes in other rows are left untouched since
 * {@code V[ref] = 0} makes their contribution vanish. Completely unstamped
 * nodes are singleton components and are tied to zero by the same mechanism
 * as a pure numerical safety net. All steps run inside the Newton loop on
 * every system build.</p>
 *
 * <p>Scope: {@link #solve()} (Newton solve), {@link #tick()} (one solve plus RK2
 * integration of element state and conductor temperature, gated on
 * {@code converged && !singular}), {@link #terminalCurrents(int)} and
 * {@link #findMeltedConductors()}.</p>
 */
public final class ElectricalKernel {
    /**
     * Lower bound for conductor resistance (ohm) used consistently by the nodal
     * system and by the conductor heating model, so the power dissipated in a
     * conductor matches the current the solver actually pushed through it.
     */
    static final double MIN_CONDUCTOR_R_OHM = 1e-4;

    /**
     * Result of one kernel (Newton) solve.
     *
     * @param voltage last Newton iterate returned by {@code solve()}; not guaranteed converged
     * @param converged true when the Newton loop met step and residual tolerances without singularity
     * @param singular true if any linear solve reported a singular pivot
     * @param residual normalized residual of the final iterate
     * @param newtonIterations number of calls to the linear solver (every call counted, singular included)
     * @param linearEliminationSteps total Gaussian-elimination row updates across all Newton iterations
     * @param fallbackActive true when a numerical-stability fallback stamp was active
     *        at the returned operating point (final iteration's system build only)
     */
    public record KernelSolveResult(Complex[] voltage, boolean converged, boolean singular,
                                    double residual, int newtonIterations, int linearEliminationSteps,
                                    boolean fallbackActive) {
    }

    private List<ElectricalElement> elements = List.of();
    private List<int[]> elementTerminals = List.of();
    private final List<double[]> elementStates = new ArrayList<>();
    private List<Conductor> conductors = List.of();
    private int nodeCount;
    private int referenceNode;
    private double omega = GridConstants.AC_OMEGA_RAD_PER_S;
    private Complex[] pendingInitialVoltage;
    private Complex[] lastSolution;
    private int[] lastNodeComponents;

    /**
     * Replaces the element topology.
     *
     * <p>Clears all element state (fresh zero arrays sized by
     * {@link ElectricalElement#stateCount()}) and resets the last solution to
     * null. State preservation across topology replacement is exclusively the
     * adapter's responsibility: snapshot via {@link #getElementState(int)}
     * before calling and restore via {@link #setElementState(int, double[])}
     * after calling.</p>
     *
     * @param newElements elements
     * @param terminals per-element terminal index arrays; each must match the element's terminal count
     * @throws IllegalArgumentException on size or terminal-count mismatch
     */
    public void setElements(List<ElectricalElement> newElements, List<int[]> terminals) {
        Objects.requireNonNull(newElements, "newElements");
        Objects.requireNonNull(terminals, "terminals");
        if (newElements.size() != terminals.size()) {
            throw new IllegalArgumentException(
                    "Elements/terminals size mismatch: " + newElements.size() + " vs " + terminals.size());
        }
        List<ElectricalElement> elementsCopy = new ArrayList<>(newElements.size());
        List<int[]> termCopy = new ArrayList<>(newElements.size());
        for (int k = 0; k < newElements.size(); k++) {
            ElectricalElement e = Objects.requireNonNull(newElements.get(k), "newElements[" + k + "]");
            int[] t = Objects.requireNonNull(terminals.get(k), "terminals[" + k + "]");
            if (t.length != e.terminalCount()) {
                throw new IllegalArgumentException("Element " + k + ": terminalCount=" + e.terminalCount()
                        + " but terminals length=" + t.length);
            }
            elementsCopy.add(e);
            termCopy.add(t.clone());
        }
        this.elements = List.copyOf(elementsCopy);
        this.elementTerminals = List.copyOf(termCopy);
        this.elementStates.clear();
        for (ElectricalElement e : this.elements) {
            this.elementStates.add(new double[e.stateCount()]);
        }
        this.lastSolution = null;
    }

    /** Replaces the conductor list (defensive copy). */
    public void setConductors(List<Conductor> newConductors) {
        Objects.requireNonNull(newConductors, "newConductors");
        for (int k = 0; k < newConductors.size(); k++) {
            Objects.requireNonNull(newConductors.get(k), "newConductors[" + k + "]");
        }
        this.conductors = List.copyOf(newConductors);
    }

    /**
     * Sets the node count. May be called before or after {@link #setElements}.
     *
     * @param n node count, must be {@code >= 0}
     * @throws IllegalArgumentException if {@code n < 0}
     */
    public void setNodeCount(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("nodeCount must be >= 0: " + n);
        }
        this.nodeCount = n;
    }

    /** Sets the angular frequency in rad/s used for element stamps. */
    public void setOmega(double newOmega) {
        this.omega = newOmega;
    }

    /**
     * Sets a one-shot warm-start voltage override for the next {@link #solve()}.
     * A {@code null} argument clears the pending override. The override is
     * consumed (cleared) by the next {@code solve()} and does not modify the
     * stored last solution.
     *
     * @param v warm-start voltages, or null to clear
     */
    public void setInitialVoltage(Complex[] v) {
        if (v == null) {
            this.pendingInitialVoltage = null;
            return;
        }
        Complex[] copy = new Complex[v.length];
        for (int k = 0; k < v.length; k++) {
            copy[k] = Objects.requireNonNull(v[k], "v[" + k + "]");
        }
        this.pendingInitialVoltage = copy;
    }

    /**
     * Returns a defensive copy of the kernel-owned state for element {@code index}.
     */
    public double[] getElementState(int index) {
        checkElementIndex(index);
        return elementStates.get(index).clone();
    }

    /**
     * Replaces the kernel-owned state for element {@code index} (defensive copy).
     *
     * @throws IllegalArgumentException on bad index or state length mismatch
     */
    public void setElementState(int index, double[] state) {
        checkElementIndex(index);
        Objects.requireNonNull(state, "state");
        int expected = elements.get(index).stateCount();
        if (state.length != expected) {
            throw new IllegalArgumentException("Element " + index + ": stateCount=" + expected
                    + " but state length=" + state.length);
        }
        elementStates.set(index, state.clone());
    }

    /** Returns a defensive copy of the last solution, or null if none. */
    public Complex[] getLastSolution() {
        return lastSolution == null ? null : lastSolution.clone();
    }

    /**
     * Runs the Newton solve and returns the result.
     *
     * @return kernel solve result carrying convergence, singularity, residual,
     *         Newton iteration count, and linear elimination step count
     * @throws IllegalArgumentException on bad terminal/conductor indices or
     *         invalid conductor physical parameters
     */
    public KernelSolveResult solve() {
        int n = nodeCount;
        if (n == 0) {
            Complex[] empty = new Complex[0];
            lastSolution = empty.clone();
            return new KernelSolveResult(empty, true, false, 0.0, 0, 0, false);
        }
        validateTerminals(n);
        validateConductors(n);
        int newtonIterations = 0;
        int totalLinearSteps = 0;
        boolean singular = false;
        boolean converged = false;
        boolean fallbackActive = false;
        double residual = Double.POSITIVE_INFINITY;
        Complex[] v = warmStart(n);
        while (newtonIterations < GridConstants.NEWTON_MAX_ITER) {
            Complex[][] y = ComplexNodalSolver.zeroMatrix(n);
            Complex[] inj = ComplexNodalSolver.zeroVector(n);
            buildSystem(y, inj, v);
            double resBefore = ComplexNodalSolver.normalizedResidual(y, inj, v);
            SolveResult res = ComplexNodalSolver.solve(y, inj);
            totalLinearSteps += res.linearEliminationSteps();
            if (res.singular()) {
                singular = true;
            }
            newtonIterations++;
            Complex[] vNew = res.voltage();
            if (!allFinite(vNew)) {
                break;
            }
            double maxDelta = 0.0;
            for (int k = 0; k < n; k++) {
                maxDelta = Math.max(maxDelta, vNew[k].sub(v[k]).magnitude());
            }
            double alpha = Math.min(1.0, GridConstants.NEWTON_MAX_STEP / Math.max(maxDelta, 1e-9));
            for (int bt = 0; bt < 4; bt++) {
                Complex a = new Complex(alpha, 0.0);
                Complex[] vTry = new Complex[n];
                for (int k = 0; k < n; k++) {
                    vTry[k] = v[k].add(vNew[k].sub(v[k]).mul(a));
                }
                Complex[][] yTry = ComplexNodalSolver.zeroMatrix(n);
                Complex[] iTry = ComplexNodalSolver.zeroVector(n);
                buildSystem(yTry, iTry, vTry);
                double rTry = ComplexNodalSolver.normalizedResidual(yTry, iTry, vTry);
                if (rTry <= resBefore || rTry < GridConstants.NEWTON_RESIDUAL_FLOOR || bt == 3) {
                    v = vTry;
                    break;
                }
                alpha *= 0.5;
            }
            Complex[][] yf = ComplexNodalSolver.zeroMatrix(n);
            Complex[] injf = ComplexNodalSolver.zeroVector(n);
            Stamps.clearFallbackFlag();
            buildSystem(yf, injf, v);
            fallbackActive = Stamps.isFallbackFlagSet();
            residual = ComplexNodalSolver.normalizedResidual(yf, injf, v);
            if (maxDelta < GridConstants.NEWTON_TOL && !singular
                    && Double.isFinite(residual) && residual < GridConstants.LINEAR_RESIDUAL_TOL) {
                converged = true;
                break;
            }
        }
        if (singular) {
            converged = false;
        }
        lastSolution = v.clone();
        return new KernelSolveResult(v, converged, singular, residual, newtonIterations, totalLinearSteps,
                fallbackActive);
    }

    /** Builds the nodal system for voltage iterate {@code v}. */
    void buildSystem(Complex[][] y, Complex[] inj, Complex[] v) {
        for (Conductor c : conductors) {
            double r = Math.max(c.resistance(), MIN_CONDUCTOR_R_OHM);
            Stamps.admittance(y, c.nodeA(), c.nodeB(), new Complex(1.0 / r, 0.0));
        }
        for (int idx = 0; idx < elements.size(); idx++) {
            int[] t = elementTerminals.get(idx);
            int[] active = t;
            if (t.length == 4) {
                boolean port0Open = !hasReturnPath(t[0], t[1], idx, 0);
                if (port0Open) {
                    active = t.clone();
                    active[0] = active[1] = t[0];
                }
            }
            elements.get(idx).stamp(y, inj, active, v, elementStates.get(idx), omega);
        }
        if (nodeCount <= 0) {
            return;
        }
        int ref = referenceNode;
        if (ref < 0 || ref >= nodeCount) {
            ref = 0;
        }
        boolean[] visited = new boolean[nodeCount];
        int[] components = new int[nodeCount];
        int currentComp = 0;
        Deque<Integer> stack = new ArrayDeque<>();
        visited[ref] = true;
        components[ref] = currentComp;
        stack.push(ref);
        while (!stack.isEmpty()) {
            int i = stack.pop();
            for (int j = 0; j < nodeCount; j++) {
                if (j == i || visited[j]) {
                    continue;
                }
                Complex a = y[i][j];
                Complex b = y[j][i];
                if ((a.re != 0.0 || a.im != 0.0) || (b.re != 0.0 || b.im != 0.0)) {
                    visited[j] = true;
                    components[j] = currentComp;
                    stack.push(j);
                }
            }
        }
        for (int j = 0; j < nodeCount; j++) {
            y[ref][j] = j == ref ? Complex.ONE : Complex.ZERO;
        }
        inj[ref] = Complex.ZERO;
        for (int i = 0; i < nodeCount; i++) {
            if (visited[i]) {
                continue;
            }
            currentComp++;
            List<Integer> component = new ArrayList<>();
            Deque<Integer> work = new ArrayDeque<>();
            visited[i] = true;
            components[i] = currentComp;
            work.push(i);
            component.add(i);
            while (!work.isEmpty()) {
                int a = work.pop();
                for (int j = 0; j < nodeCount; j++) {
                    if (j == a || visited[j]) {
                        continue;
                    }
                    Complex u = y[a][j];
                    Complex w = y[j][a];
                    if ((u.re != 0.0 || u.im != 0.0) || (w.re != 0.0 || w.im != 0.0)) {
                        visited[j] = true;
                        components[j] = currentComp;
                        work.push(j);
                        component.add(j);
                    }
                }
            }
            int compRef = component.get(0);
            for (int m : component) {
                if (m < compRef) {
                    compRef = m;
                }
            }
            for (int j = 0; j < nodeCount; j++) {
                y[compRef][j] = j == compRef ? Complex.ONE : Complex.ZERO;
            }
            inj[compRef] = Complex.ZERO;
        }
        this.lastNodeComponents = components;
    }

    /**
     * Advances the simulation by one fixed step ({@link GridConstants#DT}).
     *
     * <p>Exactly one network solve is performed per tick (item 15). If the
     * solve reports {@code !converged || singular} this method returns
     * immediately without integrating any element state or conductor
     * temperature (item 6). The gate deliberately ignores
     * {@code fallbackActive}: whenever {@code converged && !singular} the
     * tick integrates, even on a fallback operating point (the framework
     * does not act on the flag).</p>
     *
     * <p>Both RK2 stages evaluate derivatives at the SAME operating point:
     * the single converged voltage vector {@code V} is shared by
     * {@link #integrateElements} and {@link #integrateConductors}, hence
     * terminal voltages/currents, conductor resistances, and resistive
     * power are identical in both stages (items 15, 20).</p>
     */
    public KernelSolveResult tick() {
        KernelSolveResult result = solve();
        if (!result.converged() || result.singular()) {
            return result;
        }
        Complex[] V = result.voltage();
        integrateElements(V, GridConstants.DT);
        integrateConductors(V, GridConstants.DT);
        return result;
    }

    /**
     * Integrates element state with one RK2 (midpoint) step at a fixed
     * operating point {@code v}.
     *
     * <p>Per element: {@code Vt} is gathered fresh from {@code v} and
     * {@code It} is computed once via the item-22 formula, so both RK2
     * stages share the same operating point (item 15). Purity strategy
     * (item 28): fresh {@code Vt}/{@code It} arrays are built per element;
     * {@code derivatives} receives only clones of them, so a mutating
     * element cannot corrupt the second stage's inputs; the second stage
     * evaluates on a {@code mid} copy ({@code state + 0.5*dt*k1}), never an
     * alias of kernel state; the kernel-owned {@code state} array is passed
     * for reading only and written solely by the final
     * {@code state[i] += dt*k2[i]} update. Zero-length state is a no-op.</p>
     *
     * @param v converged operating-point voltages, length = node count
     * @param dt timestep in seconds
     */
    void integrateElements(Complex[] v, double dt) {
        for (int idx = 0; idx < elements.size(); idx++) {
            ElectricalElement element = elements.get(idx);
            int[] terms = elementTerminals.get(idx);
            double[] state = elementStates.get(idx);
            int k = element.terminalCount();
            Complex[] vt = new Complex[k];
            for (int j = 0; j < k; j++) {
                vt[j] = v[terms[j]];
            }
            sanitizeTerminalVoltages(terms, vt, k, idx);
            Complex[] it = terminalCurrents(idx, v);
            double[] k1 = new double[state.length];
            element.derivatives(k1, state, vt.clone(), it.clone());
            double[] mid = new double[state.length];
            for (int i = 0; i < state.length; i++) {
                mid[i] = state[i] + 0.5 * dt * k1[i];
            }
            double[] k2 = new double[state.length];
            element.derivatives(k2, mid, vt.clone(), it.clone());
            for (int i = 0; i < state.length; i++) {
                state[i] += dt * k2[i];
            }
        }
    }

    /**
     * Integrates conductor temperatures with one RK2 (midpoint) step at a
     * fixed operating point {@code v}.
     *
     * <p>Resistance is read once per tick per conductor and the average
     * resistive power {@code powerLossW = |dV|^2 / R} is identical in both
     * stages because both use the same {@code v} (item 20). {@code R} is
     * clamped to {@link #MIN_CONDUCTOR_R_OHM}, exactly as in
     * {@link #buildSystem}: the solver computed {@code dV} with the clamped
     * resistance, so dividing by the unclamped one would overstate the
     * dissipated power and overheat low-resistance conductors. Only the
     * cooling term is re-evaluated at the midpoint temperature.</p>
     *
     * @param v converged operating-point voltages, length = node count
     * @param dt timestep in seconds
     */
    void integrateConductors(Complex[] v, double dt) {
        for (Conductor c : conductors) {
            double r = Math.max(c.resistance(), MIN_CONDUCTOR_R_OHM);
            Complex dv = v[c.nodeA()].sub(v[c.nodeB()]);
            double powerLossW = dv.magnitudeSquared() / r;
            double t = c.temperature();
            double cap = c.heatCapacity();
            double kc = c.coolingCoeff();
            double d1 = (powerLossW - kc * (t - GridConstants.AMBIENT_C)) / cap;
            double tm = t + 0.5 * dt * d1;
            double d2 = (powerLossW - kc * (tm - GridConstants.AMBIENT_C)) / cap;
            c.setTemperature(t + dt * d2);
        }
    }

    /**
     * Returns the terminal currents represented by the converged stamp
     * (item 14): the currents of the element's linearization at the last
     * solved operating point. Positive means current entering the element
     * at that terminal.
     *
     * @param elementIndex element index
     * @return fresh terminal-current array, length = terminal count
     * @throws IllegalArgumentException on a bad element index
     * @throws IllegalStateException if no solution exists yet
     */
    public Complex[] terminalCurrents(int elementIndex) {
        checkElementIndex(elementIndex);
        Complex[] vlast = getLastSolution();
        if (vlast == null) {
            throw new IllegalStateException("no solution yet");
        }
        return terminalCurrents(elementIndex, vlast);
    }

    /**
     * Returns the terminal currents represented by the converged stamp at
     * the supplied operating point {@code v} (item 14).
     *
     * <p>Item-22 formula, implemented exactly: the element receives local
     * terminal indices {@code [0..k-1]} and local voltages {@code Vt}; it
     * never receives global node indices. Fresh local arrays are built,
     * {@code Yl} (zero {@code k x k}) and {@code Il} (zero length
     * {@code k}), the element stamps into them, and
     * {@code It[j] = sum_m Yl[j][m]*Vt[m] - Il[j]}. No reference-node tie or other
     * element contributes.</p>
     *
     * <p>The local stamp may set the {@link Stamps} fallback flag, which
     * must not pollute {@code solve()}'s {@code fallbackActive}: the flag
     * is cleared before the local stamp and cleared again (discarded) after
     * {@code It} is computed.</p>
     *
     * @param elementIndex element index
     * @param v operating-point voltages, length = node count
     * @return fresh terminal-current array, length = terminal count
     * @throws IllegalArgumentException on a bad element index or a terminal
     *         index outside {@code v}
     */
    public Complex[] terminalCurrents(int elementIndex, Complex[] v) {
        checkElementIndex(elementIndex);
        Objects.requireNonNull(v, "v");
        ElectricalElement element = elements.get(elementIndex);
        int[] terms = elementTerminals.get(elementIndex);
        int k = element.terminalCount();
        Complex[] vt = new Complex[k];
        for (int j = 0; j < k; j++) {
            int t = terms[j];
            if (t < 0 || t >= v.length) {
                throw new IllegalArgumentException("Element " + elementIndex
                        + ": terminal index out of range: " + t + " (n=" + v.length + ")");
            }
            vt[j] = Objects.requireNonNull(v[t], "v[" + t + "]");
        }
        sanitizeTerminalVoltages(terms, vt, k, elementIndex);
        Complex[][] yl = ComplexNodalSolver.zeroMatrix(k);
        Complex[] il = ComplexNodalSolver.zeroVector(k);
        int[] local = new int[k];
        for (int j = 0; j < k; j++) {
            local[j] = j;
        }
        Stamps.clearFallbackFlag();
        element.stamp(yl, il, local, vt, elementStates.get(elementIndex), omega);
        Stamps.clearFallbackFlag();
        Complex[] it = new Complex[k];
        boolean port0Open = k >= 4 && !hasReturnPath(terms[0], terms[1], elementIndex, 0);
        boolean port1Open = k >= 4 && !hasReturnPath(terms[2], terms[3], elementIndex, 1);
        for (int j = 0; j < k; j++) {
            if ((j < 2 && port0Open) || (j >= 2 && port1Open)) {
                it[j] = Complex.ZERO;
                continue;
            }
            Complex acc = Complex.ZERO;
            for (int m = 0; m < k; m++) {
                acc = acc.add(yl[j][m].mul(vt[m]));
            }
            it[j] = acc.sub(il[j]);
        }
        return it;
    }

    boolean hasReturnPath(int termA, int termB, int excludeElementIndex, int excludePort) {
        if (nodeCount <= 0 || termA < 0 || termA >= nodeCount || termB < 0 || termB >= nodeCount) {
            return false;
        }
        if (termA == termB) {
            return true;
        }
        List<List<Integer>> adj = new ArrayList<>(nodeCount);
        for (int i = 0; i < nodeCount; i++) {
            adj.add(new ArrayList<>());
        }
        for (Conductor c : conductors) {
            adj.get(c.nodeA()).add(c.nodeB());
            adj.get(c.nodeB()).add(c.nodeA());
        }
        for (int i = 0; i < elements.size(); i++) {
            int[] t = elementTerminals.get(i);
            if (t.length >= 2) {
                if (i != excludeElementIndex || excludePort != 0) {
                    adj.get(t[0]).add(t[1]);
                    adj.get(t[1]).add(t[0]);
                }
            }
            if (t.length >= 4) {
                if (i != excludeElementIndex || excludePort != 1) {
                    adj.get(t[2]).add(t[3]);
                    adj.get(t[3]).add(t[2]);
                }
            }
        }
        boolean[] visited = new boolean[nodeCount];
        Deque<Integer> queue = new ArrayDeque<>();
        visited[termA] = true;
        queue.add(termA);
        while (!queue.isEmpty()) {
            int curr = queue.poll();
            if (curr == termB) {
                return true;
            }
            for (int neighbor : adj.get(curr)) {
                if (!visited[neighbor]) {
                    visited[neighbor] = true;
                    queue.add(neighbor);
                }
            }
        }
        return false;
    }

    private void sanitizeTerminalVoltages(int[] terms, Complex[] vt, int k, int elemIdx) {
        if (k >= 4 && terms.length >= 4) {
            if (!hasReturnPath(terms[0], terms[1], elemIdx, 0)
                    || (lastNodeComponents != null && lastNodeComponents[terms[0]] != lastNodeComponents[terms[1]])) {
                vt[0] = Complex.ZERO;
                vt[1] = Complex.ZERO;
            }
        }
    }

    /**
     * Returns every conductor whose temperature strictly exceeds its
     * melting temperature ({@code temperature() > meltingTemp()}, not
     * {@code >=}).
     *
     * <p>Side-effect free (item 13): no temperature is changed, no kernel
     * data is mutated, and a new list is returned on every call.</p>
     *
     * @return new list of melted conductors (possibly empty)
     */
    public List<Conductor> findMeltedConductors() {
        List<Conductor> out = new ArrayList<>();
        for (Conductor c : conductors) {
            if (c.temperature() > c.meltingTemp()) {
                out.add(c);
            }
        }
        return out;
    }

    private Complex[] warmStart(int n) {
        if (pendingInitialVoltage != null) {
            Complex[] w = pendingInitialVoltage;
            pendingInitialVoltage = null;
            if (w.length == n) {
                return w.clone();
            }
        }
        if (lastSolution != null && lastSolution.length == n) {
            return lastSolution.clone();
        }
        return ComplexNodalSolver.zeroVector(n);
    }

    private static boolean allFinite(Complex[] v) {
        for (Complex c : v) {
            if (c == null || !c.isFinite()) {
                return false;
            }
        }
        return true;
    }

    private void validateTerminals(int n) {
        for (int idx = 0; idx < elementTerminals.size(); idx++) {
            for (int t : elementTerminals.get(idx)) {
                if (t < 0 || t >= n) {
                    throw new IllegalArgumentException(
                            "Element " + idx + ": terminal index out of range: " + t + " (n=" + n + ")");
                }
            }
        }
    }

    private void validateConductors(int n) {
        for (int k = 0; k < conductors.size(); k++) {
            Conductor c = conductors.get(k);
            if (c.nodeA() < 0 || c.nodeA() >= n || c.nodeB() < 0 || c.nodeB() >= n) {
                throw new IllegalArgumentException("Conductor " + k + ": node index out of range: ("
                        + c.nodeA() + "," + c.nodeB() + ") (n=" + n + ")");
            }
            if (!Double.isFinite(c.resistance()) || c.resistance() <= 0.0) {
                throw new IllegalArgumentException(
                        "Conductor " + k + ": resistance must be finite and > 0: " + c.resistance());
            }
            if (!Double.isFinite(c.heatCapacity()) || c.heatCapacity() <= 0.0) {
                throw new IllegalArgumentException(
                        "Conductor " + k + ": heatCapacity must be finite and > 0: " + c.heatCapacity());
            }
            if (!Double.isFinite(c.coolingCoeff()) || c.coolingCoeff() < 0.0) {
                throw new IllegalArgumentException(
                        "Conductor " + k + ": coolingCoeff must be finite and >= 0: " + c.coolingCoeff());
            }
            if (!Double.isFinite(c.meltingTemp())) {
                throw new IllegalArgumentException(
                        "Conductor " + k + ": meltingTemp must be finite: " + c.meltingTemp());
            }
        }
    }

    private void checkElementIndex(int index) {
        if (index < 0 || index >= elements.size()) {
            throw new IllegalArgumentException("Element index out of range: " + index);
        }
    }
}