package com.ostapyrih.voltcraft.simulation.solver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Modified Nodal Analysis (MNA) solver for linear electrical networks.
 * Formulates and solves G * x = z for nodal voltages and auxiliary branch currents.
 */
public class ModifiedNodalAnalysis {

    public record ResistorBranch(int nodeA, int nodeB, double resistance) {}
    public record VoltageSource(int positiveNode, int negativeNode, double voltage) {}
    public record CurrentSource(int fromNode, int toNode, double current) {}

    public static class Circuit {
        private final int numNodes; // excluding reference node 0 (ground)
        private final List<ResistorBranch> resistors = new ArrayList<>();
        private final List<VoltageSource> voltageSources = new ArrayList<>();
        private final List<CurrentSource> currentSources = new ArrayList<>();

        /**
         * @param numNodes Number of non-ground nodes (indexed 1 to numNodes). Node 0 is ground (0V).
         */
        public Circuit(int numNodes) {
            this.numNodes = numNodes;
        }

        public void addResistor(int nodeA, int nodeB, double resistance) {
            if (resistance <= 0.0) {
                resistance = 1e-6; // guard against division by zero
            }
            resistors.add(new ResistorBranch(nodeA, nodeB, resistance));
        }

        public void addVoltageSource(int positiveNode, int negativeNode, double voltage) {
            voltageSources.add(new VoltageSource(positiveNode, negativeNode, voltage));
        }

        public void addCurrentSource(int fromNode, int toNode, double current) {
            currentSources.add(new CurrentSource(fromNode, toNode, current));
        }

        public int getNumNodes() {
            return numNodes;
        }
    }

    public record Solution(double[] nodeVoltages, double[] voltageSourceCurrents) {
        public double getNodeVoltage(int nodeIndex) {
            if (nodeIndex == 0) return 0.0;
            if (nodeIndex < 0 || nodeIndex >= nodeVoltages.length) return 0.0;
            return nodeVoltages[nodeIndex];
        }
    }

    /**
     * Solves the MNA matrix equation for the given circuit.
     * Node 0 is always fixed to 0.0V (ground reference).
     */
    public static Solution solve(Circuit circuit) {
        int n = circuit.numNodes;
        int m = circuit.voltageSources.size();
        int totalSize = n + m;

        if (totalSize == 0) {
            return new Solution(new double[]{0.0}, new double[0]);
        }

        double[][] A = new double[totalSize][totalSize];
        double[] z = new double[totalSize];

        // 1. Stamp Conductance Matrix G (n x n)
        for (ResistorBranch r : circuit.resistors) {
            double g = 1.0 / r.resistance();
            int nA = r.nodeA();
            int nB = r.nodeB();

            if (nA > 0) {
                A[nA - 1][nA - 1] += g;
            }
            if (nB > 0) {
                A[nB - 1][nB - 1] += g;
            }
            if (nA > 0 && nB > 0) {
                A[nA - 1][nB - 1] -= g;
                A[nB - 1][nA - 1] -= g;
            }
        }

        // 2. Stamp Current Sources into RHS vector z
        for (CurrentSource cs : circuit.currentSources) {
            if (cs.fromNode() > 0) {
                z[cs.fromNode() - 1] -= cs.current();
            }
            if (cs.toNode() > 0) {
                z[cs.toNode() - 1] += cs.current();
            }
        }

        // 3. Stamp Voltage Sources (B and C matrices + RHS E vector)
        for (int i = 0; i < m; i++) {
            VoltageSource vs = circuit.voltageSources.get(i);
            int k = n + i;
            int nPos = vs.positiveNode();
            int nNeg = vs.negativeNode();

            if (nPos > 0) {
                A[nPos - 1][k] += 1.0;
                A[k][nPos - 1] += 1.0;
            }
            if (nNeg > 0) {
                A[nNeg - 1][k] -= 1.0;
                A[k][nNeg - 1] -= 1.0;
            }
            z[k] = vs.voltage();
        }

        // 4. Solve A * x = z via Gaussian elimination with partial pivoting
        double[] x = solveLinearSystem(A, z);

        double[] nodeVoltages = new double[n + 1];
        nodeVoltages[0] = 0.0; // Ground reference
        System.arraycopy(x, 0, nodeVoltages, 1, n);

        double[] vsCurrents = new double[m];
        System.arraycopy(x, n, vsCurrents, 0, m);

        return new Solution(nodeVoltages, vsCurrents);
    }

    private static double[] solveLinearSystem(double[][] A, double[] b) {
        int n = b.length;
        double[][] M = new double[n][n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(A[i], 0, M[i], 0, n);
        }
        double[] rhs = Arrays.copyOf(b, n);

        // Forward elimination with partial pivoting
        for (int p = 0; p < n; p++) {
            int maxRow = p;
            double maxVal = Math.abs(M[p][p]);
            for (int i = p + 1; i < n; i++) {
                double val = Math.abs(M[i][p]);
                if (val > maxVal) {
                    maxVal = val;
                    maxRow = i;
                }
            }

            if (maxVal < 1e-12) {
                // Singular matrix or floating island; apply Tikhonov regularization
                M[p][p] += 1e-6;
            } else if (maxRow != p) {
                double[] tempRow = M[p];
                M[p] = M[maxRow];
                M[maxRow] = tempRow;
                double tempRhs = rhs[p];
                rhs[p] = rhs[maxRow];
                rhs[maxRow] = tempRhs;
            }

            for (int i = p + 1; i < n; i++) {
                double factor = M[i][p] / M[p][p];
                rhs[i] -= factor * rhs[p];
                for (int j = p; j < n; j++) {
                    M[i][j] -= factor * M[p][j];
                }
            }
        }

        // Back substitution
        double[] x = new double[n];
        for (int i = n - 1; i >= 0; i--) {
            double sum = 0.0;
            for (int j = i + 1; j < n; j++) {
                sum += M[i][j] * x[j];
            }
            if (Math.abs(M[i][i]) > 1e-12) {
                x[i] = (rhs[i] - sum) / M[i][i];
            } else {
                x[i] = 0.0;
            }
        }
        return x;
    }
}
