package com.ostapyrih.voltcraft.simulation.grid;

import com.ostapyrih.voltcraft.api.data.ElectricalState;
import com.ostapyrih.voltcraft.api.energy.IElectricConsumer;
import com.ostapyrih.voltcraft.api.energy.IElectricSource;
import com.ostapyrih.voltcraft.api.energy.IElectricStorage;
import com.ostapyrih.voltcraft.api.grid.IElectricalGrid;
import com.ostapyrih.voltcraft.block.cable.CableBlock;
import com.ostapyrih.voltcraft.block.entity.conversion.AbstractPowerConverterBlockEntity;
import com.ostapyrih.voltcraft.simulation.solver.ModifiedNodalAnalysis;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.*;

/**
 * Concrete implementation of an isolated electrical network graph.
 * Performs centralized, non-wire-ticking physics calculations once per tick.
 *
 * <p>Each {@link #tick(ServerWorld)} runs a two-pass Modified Nodal Analysis solve:
 * <ol>
 *   <li><b>Pass 1 (detector):</b> every non-storage source is stamped as its Thevenin
 *       equivalent (ideal EMF + internal resistance) and the network is solved once.</li>
 *   <li><b>Pass 2 (corrector, only if needed):</b> any non-storage source whose pass-1
 *       current exceeds what it can sustain is re-stamped as an ideal current source
 *       pushing exactly its available current; any with reverse pass-1 current is
 *       re-stamped as an open circuit (an output diode blocks a floated charger from
 *       sourcing or sinking). Storage keeps its Thevenin behavior in both passes so
 *       fault currents (shorts, surges) still obey Ohm's law. The corrector is
 *       stateless: pass 1 acts purely as a detector, so there is no latch to chatter,
 *       strobe, or deadlock, and a static network reproduces the identical corrected
 *       result every tick.</li>
 * </ol>
 */
public class ElectricalGrid implements IElectricalGrid {

    /** Fixed physics timestep this grid assumes per tick. */
    private static final double TICK_DELTA_SECONDS = 0.05;
    private static final double AMBIENT_TEMPERATURE_C = 20.0;

    /** Below this magnitude, a current is treated as numerical noise, not real flow. */
    private static final double CURRENT_EPSILON = 1e-4;

    /** Near-zero resistance used to tie an explicit ground node to the MNA reference node. */
    private static final double GROUND_TIE_RESISTANCE = 1e-4;

    /**
     * Resistance stamped to ground for any node that ends up with no other connection
     * (all conductors/consumers/sources removed or never present). Without this, such a
     * node contributes an all-zero row/column to the conductance matrix, which the solver
     * cannot invert. High enough to be electrically inert, low enough to keep the matrix
     * well-conditioned.
     */
    private static final double FLOATING_NODE_SAFETY_RESISTANCE = 1e12;

    /**
     * A source's pass-1 current must exceed its available current by this fraction before
     * the corrector engages. Prevents chatter from numerical noise around the boundary
     * while still clamping genuine overloads and delivering exact constant current.
     */
    private static final double CORRECTOR_ENGAGE_MARGIN = 1.02;

    private final UUID gridId;
    private final Map<BlockPos, GridNode> nodes = new HashMap<>();
    private final List<GridConductor> conductors = new ArrayList<>();
    private final Map<BlockPos, List<IElectricSource>> sources = new HashMap<>();
    private final Map<BlockPos, List<IElectricConsumer>> consumers = new HashMap<>();

    private double totalGenerationWatts = 0.0;
    private double totalConsumptionWatts = 0.0;
    private double gridFrequency = 0.0;
    private boolean stable = true;

    public ElectricalGrid(UUID gridId) {
        this.gridId = gridId;
    }

    public ElectricalGrid() {
        this(UUID.randomUUID());
    }

    // ---------------------------------------------------------------------
    // Topology mutation
    // ---------------------------------------------------------------------

    @Override
    public UUID getGridId() {
        return gridId;
    }

    @Override
    public double getFrequency() {
        return gridFrequency;
    }

    public void addNode(BlockPos pos, boolean ground) {
        nodes.computeIfAbsent(pos.toImmutable(), p -> new GridNode(p, nodes.size(), ground));
    }

    public void removeNode(BlockPos pos) {
        nodes.remove(pos);
        conductors.removeIf(c -> c.getStartPos().equals(pos) || c.getEndPos().equals(pos));
        sources.remove(pos);
        consumers.remove(pos);
    }

    public void addConductor(GridConductor conductor) {
        addNode(conductor.getStartPos(), false);
        addNode(conductor.getEndPos(), false);
        conductors.add(conductor);
    }

    public void registerSource(BlockPos pos, IElectricSource source) {
        addNode(pos, false);
        addUnique(sources.computeIfAbsent(pos.toImmutable(), p -> new ArrayList<>()), source);
    }

    public void unregisterSource(BlockPos pos) {
        sources.remove(pos);
    }

    public void unregisterSource(BlockPos pos, IElectricSource source) {
        removeAndPruneIfEmpty(sources, pos, source);
    }

    public void unregisterSource(IElectricSource source) {
        removeFromAllLists(sources, source);
    }

    public void registerConsumer(BlockPos pos, IElectricConsumer consumer) {
        addNode(pos, false);
        addUnique(consumers.computeIfAbsent(pos.toImmutable(), p -> new ArrayList<>()), consumer);
    }

    public void unregisterConsumer(BlockPos pos) {
        consumers.remove(pos);
    }

    public void unregisterConsumer(BlockPos pos, IElectricConsumer consumer) {
        removeAndPruneIfEmpty(consumers, pos, consumer);
    }

    public void unregisterConsumer(IElectricConsumer consumer) {
        removeFromAllLists(consumers, consumer);
    }

    private static <T> void addUnique(List<T> list, T item) {
        if (!list.contains(item)) {
            list.add(item);
        }
    }

    private static <T> void removeAndPruneIfEmpty(Map<BlockPos, List<T>> map, BlockPos pos, T item) {
        List<T> list = map.get(pos);
        if (list != null) {
            list.remove(item);
            if (list.isEmpty()) {
                map.remove(pos);
            }
        }
    }

    private static <T> void removeFromAllLists(Map<BlockPos, List<T>> map, T item) {
        for (Iterator<Map.Entry<BlockPos, List<T>>> it = map.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<BlockPos, List<T>> entry = it.next();
            entry.getValue().remove(item);
            if (entry.getValue().isEmpty()) {
                it.remove();
            }
        }
    }

    // ---------------------------------------------------------------------
    // Read-only accessors
    // ---------------------------------------------------------------------

    @Override
    public Collection<BlockPos> getNodePositions() {
        return Collections.unmodifiableSet(nodes.keySet());
    }

    @Override
    public double getNodeVoltage(BlockPos pos) {
        GridNode node = nodes.get(pos);
        return node != null ? node.getVoltage() : 0.0;
    }

    public List<GridConductor> getConductors() {
        return Collections.unmodifiableList(conductors);
    }

    public Map<BlockPos, List<IElectricSource>> getSources() {
        return Collections.unmodifiableMap(sources);
    }

    public Map<BlockPos, List<IElectricConsumer>> getConsumers() {
        return Collections.unmodifiableMap(consumers);
    }

    @Override
    public double getTotalGenerationWatts() {
        return totalGenerationWatts;
    }

    @Override
    public double getTotalConsumptionWatts() {
        return totalConsumptionWatts;
    }

    @Override
    public boolean contains(BlockPos pos) {
        return nodes.containsKey(pos);
    }

    @Override
    public boolean isStable() {
        return stable;
    }

    // ---------------------------------------------------------------------
    // Tick pipeline
    // ---------------------------------------------------------------------

    @Override
    public void tick(ServerWorld world) {
        if (nodes.isEmpty()) {
            return;
        }

        pruneRemovedEntities();

        // Map discrete positions to sequential 1-based integer indices for the MNA matrix.
        // Node 0 is reserved as the common Ground reference (0.0V).
        List<BlockPos> posIndexList = new ArrayList<>(nodes.keySet());
        Map<BlockPos, Integer> posToMnaNode = new HashMap<>();
        for (int i = 0; i < posIndexList.size(); i++) {
            posToMnaNode.put(posIndexList.get(i), i + 1);
        }

        // Snapshots for stable iteration while block entities register/unregister mid-tick.
        List<GridConductor> conductorList = new ArrayList<>(this.conductors);
        Map<BlockPos, List<IElectricConsumer>> consumerSnapshot = snapshot(consumers);
        Map<BlockPos, List<IElectricSource>> sourceSnapshot = snapshot(sources);

        this.gridFrequency = computeDominantAcFrequency(sourceSnapshot);

        // Pass 1: pure Thevenin solve, used only to detect which sources need correcting.
        ModifiedNodalAnalysis.Circuit circuit =
            buildCircuit(posIndexList, posToMnaNode, conductorList, consumerSnapshot, sourceSnapshot, null, null);
        ModifiedNodalAnalysis.Solution solution = ModifiedNodalAnalysis.solve(circuit);

        Map<IElectricSource, Double> currentOverrides = new IdentityHashMap<>();
        Set<IElectricSource> openSources = Collections.newSetFromMap(new IdentityHashMap<>());
        findSourcesNeedingCorrection(sourceSnapshot, posToMnaNode, solution, currentOverrides, openSources);

        // Pass 2: re-solve only if the corrector actually found something to fix.
        if (!currentOverrides.isEmpty() || !openSources.isEmpty()) {
            circuit = buildCircuit(posIndexList, posToMnaNode, conductorList,
                consumerSnapshot, sourceSnapshot, currentOverrides, openSources);
            solution = ModifiedNodalAnalysis.solve(circuit);
        }

        applyVoltagesToNodes(posIndexList, posToMnaNode, solution);

        WorldHazards hazards = new WorldHazards();
        updateConductorCurrentsAndThermals(world, conductorList, hazards);

        this.totalConsumptionWatts = dispatchPowerToConsumers(consumerSnapshot);
        this.totalGenerationWatts = updateSources(sourceSnapshot, posToMnaNode, solution, currentOverrides, openSources);

        this.stable = true;

        applyWorldHazards(world, hazards);
    }

    private static <T> Map<BlockPos, List<T>> snapshot(Map<BlockPos, List<T>> source) {
        Map<BlockPos, List<T>> copy = new HashMap<>();
        for (Map.Entry<BlockPos, List<T>> entry : source.entrySet()) {
            copy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return copy;
    }

    /** Drops consumers/sources whose backing block entity has been removed from the world. */
    private void pruneRemovedEntities() {
        consumers.values().forEach(list -> list.removeIf(ElectricalGrid::isConsumerGone));
        consumers.entrySet().removeIf(entry -> entry.getValue().isEmpty());

        sources.values().forEach(list -> list.removeIf(ElectricalGrid::isSourceGone));
        sources.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    private static boolean isConsumerGone(IElectricConsumer consumer) {
        if (consumer instanceof BlockEntity be && be.isRemoved()) return true;
        return consumer instanceof AbstractPowerConverterBlockEntity.InputConsumer ic && ic.isRemoved();
    }

    private static boolean isSourceGone(IElectricSource source) {
        if (source instanceof BlockEntity be && be.isRemoved()) return true;
        return source instanceof AbstractPowerConverterBlockEntity.OutputSource os && os.isRemoved();
    }

    /** Finds the highest-EMF AC source and returns its frequency, or 0.0 if the grid is unpowered/DC-only. */
    private static double computeDominantAcFrequency(Map<BlockPos, List<IElectricSource>> sourceSnapshot) {
        double dominantFreq = 0.0;
        double maxEmf = 0.0;
        for (List<IElectricSource> srcList : sourceSnapshot.values()) {
            for (IElectricSource src : srcList) {
                if (src.getElectromotiveForce() > 0.5 && src.getFrequency() > 0.001
                    && src.getElectromotiveForce() > maxEmf) {
                    maxEmf = src.getElectromotiveForce();
                    dominantFreq = src.getFrequency();
                }
            }
        }
        return dominantFreq;
    }

    /**
     * Inspects the pass-1 solution and decides, per non-storage source, whether it must be
     * re-stamped as an ideal current source ({@code currentOverrides}) or an open circuit
     * ({@code openSources}) for pass 2. Storage sources are left alone: they keep their
     * Thevenin behavior so fault currents still obey Ohm's law.
     */
    private static void findSourcesNeedingCorrection(
        Map<BlockPos, List<IElectricSource>> sourceSnapshot,
        Map<BlockPos, Integer> posToMnaNode,
        ModifiedNodalAnalysis.Solution pass1Solution,
        Map<IElectricSource, Double> currentOverrides,
        Set<IElectricSource> openSources
    ) {
        for (Map.Entry<BlockPos, List<IElectricSource>> entry : sourceSnapshot.entrySet()) {
            Integer nodeIdx = posToMnaNode.get(entry.getKey());
            if (nodeIdx == null) {
                continue;
            }
            double nodeVoltage = pass1Solution.getNodeVoltage(nodeIdx);
            for (IElectricSource src : entry.getValue()) {
                if (src instanceof IElectricStorage) {
                    continue;
                }
                double emf = src.getElectromotiveForce();
                if (emf <= 0.0) {
                    continue;
                }

                double rInt = Math.max(1e-4, src.getInternalResistance());
                double pass1Current = (emf - nodeVoltage) / rInt;
                if (pass1Current < -CURRENT_EPSILON) {
                    // Reverse current: an output diode would block this entirely.
                    openSources.add(src);
                    continue;
                }

                double avail = src.getAvailableOutputCurrent();
                if (Double.isFinite(avail) && avail <= CURRENT_EPSILON) {
                    openSources.add(src);
                    continue;
                }

                boolean isCurrentRegulated = src instanceof AbstractPowerConverterBlockEntity.OutputSource os
                    && os.isCurrentRegulated();
                boolean overloaded = pass1Current > avail * CORRECTOR_ENGAGE_MARGIN;
                boolean availIsBinding = Double.isFinite(avail) && avail > CURRENT_EPSILON && avail < emf / rInt;

                if ((isCurrentRegulated || overloaded) && availIsBinding) {
                    currentOverrides.put(src, avail);
                }
            }
        }
    }

    private void applyVoltagesToNodes(
        List<BlockPos> posIndexList,
        Map<BlockPos, Integer> posToMnaNode,
        ModifiedNodalAnalysis.Solution solution
    ) {
        for (BlockPos p : posIndexList) {
            GridNode node = nodes.get(p);
            if (node != null) {
                node.setVoltage(solution.getNodeVoltage(posToMnaNode.get(p)));
            }
        }
    }

    /** Updates conductor current/temperature and records any thermal hazards for later, safe application. */
    private void updateConductorCurrentsAndThermals(
        ServerWorld world,
        List<GridConductor> conductorList,
        WorldHazards hazards
    ) {
        List<GridConductor> burnedConductors = new ArrayList<>();

        for (GridConductor c : conductorList) {
            GridNode nodeA = nodes.get(c.getStartPos());
            GridNode nodeB = nodes.get(c.getEndPos());
            double vA = nodeA != null ? nodeA.getVoltage() : 0.0;
            double vB = nodeB != null ? nodeB.getVoltage() : 0.0;

            double current = Math.abs(vA - vB) / Math.max(1e-6, c.getEffectiveResistance());
            c.setCurrent(current);
            c.updateThermal(AMBIENT_TEMPERATURE_C, TICK_DELTA_SECONDS);

            if (c.getTemperature() >= c.getMeltingTemp()) {
                burnedConductors.add(c);
                BlockPos breakTarget = pickBreakTarget(world, c);
                hazards.blocksToBreak.add(breakTarget);
                hazards.firesToSpawn.add(breakTarget);
            } else if (c.isInsulated() && c.getTemperature() >= c.getMaxInsulationTemp()) {
                hazards.firesToSpawn.add(c.getStartPos().up());
            }
        }

        if (!burnedConductors.isEmpty()) {
            this.conductors.removeAll(burnedConductors);
        }
    }

    /** Prefers whichever endpoint is an actual cable block, so burning a wire breaks the wire, not an endpoint device. */
    private static BlockPos pickBreakTarget(ServerWorld world, GridConductor c) {
        if (world != null) {
            if (world.getBlockState(c.getStartPos()).getBlock() instanceof CableBlock) {
                return c.getStartPos();
            }
            if (world.getBlockState(c.getEndPos()).getBlock() instanceof CableBlock) {
                return c.getEndPos();
            }
        }
        return c.getStartPos();
    }

    /** Delivers power to every consumer, updates their electrical state, and returns total watts consumed. */
    private double dispatchPowerToConsumers(Map<BlockPos, List<IElectricConsumer>> consumerSnapshot) {
        double totalWatts = 0.0;

        for (Map.Entry<BlockPos, List<IElectricConsumer>> entry : consumerSnapshot.entrySet()) {
            GridNode node = nodes.get(entry.getKey());
            double terminalV = node != null ? node.getVoltage() : 0.0;

            for (IElectricConsumer consumer : entry.getValue()) {
                double deliveredI = terminalV / Math.max(1e-6, consumer.getEquivalentResistance());
                double deliveredWatts = terminalV * deliveredI;
                totalWatts += deliveredWatts;

                consumer.onPowerReceived(terminalV, deliveredI, TICK_DELTA_SECONDS, this.gridFrequency);
                consumer.setElectricalState(classifyConsumerState(consumer, terminalV));
            }
        }

        return totalWatts;
    }

    private static ElectricalState classifyConsumerState(IElectricConsumer consumer, double terminalV) {
        if (terminalV < consumer.getMinOperatingVoltage()) {
            return terminalV <= 0.1 ? ElectricalState.OFF : ElectricalState.BROWNOUT;
        }
        if (terminalV > consumer.getMaxOperatingVoltage() * 1.3) {
            return ElectricalState.DESTROYED;
        }
        if (terminalV > consumer.getMaxOperatingVoltage()) {
            return ElectricalState.SURGE;
        }
        return ElectricalState.NOMINAL;
    }

    /** Feeds actual net delivered/absorbed current back into every source and returns total watts generated. */
    private double updateSources(
        Map<BlockPos, List<IElectricSource>> sourceSnapshot,
        Map<BlockPos, Integer> posToMnaNode,
        ModifiedNodalAnalysis.Solution solution,
        Map<IElectricSource, Double> currentOverrides,
        Set<IElectricSource> openSources
    ) {
        double totalWatts = 0.0;

        for (Map.Entry<BlockPos, List<IElectricSource>> entry : sourceSnapshot.entrySet()) {
            GridNode node = nodes.get(entry.getKey());
            double v = node != null ? node.getVoltage() : 0.0;

            for (IElectricSource source : entry.getValue()) {
                if (openSources.contains(source)) {
                    // Reverse-blocked in the corrected solve: delivers nothing.
                    source.onPowerDrawn(0.0, TICK_DELTA_SECONDS);
                    continue;
                }

                Double forced = currentOverrides.get(source);
                if (forced != null) {
                    totalWatts += settleForcedCurrentSource(source, v, forced);
                    continue;
                }

                totalWatts += settleFreeRunningSource(source, v);
            }
        }

        return totalWatts;
    }

    /**
     * Settles a source the corrector pinned to an ideal current output. Still reverse-blocked
     * (output diode) if the node has floated above EMF — e.g. a full battery holding the rail
     * over a floated charger's setpoint.
     */
    private double settleForcedCurrentSource(IElectricSource source, double nodeVoltage, double forcedCurrent) {
        double emf = source.getElectromotiveForce();
        double delivered = (nodeVoltage > emf) ? 0.0 : Math.max(0.0, forcedCurrent);

        if (delivered > CURRENT_EPSILON) {
            source.onPowerDrawn(delivered, TICK_DELTA_SECONDS);
            return nodeVoltage * delivered;
        }
        source.onPowerDrawn(0.0, TICK_DELTA_SECONDS);
        return 0.0;
    }

    /** Settles a source that kept its plain Thevenin behavior through both passes. */
    private double settleFreeRunningSource(IElectricSource source, double nodeVoltage) {
        double emf = source.getElectromotiveForce();
        double rInt = Math.max(1e-4, source.getInternalResistance());
        double netCurrent = (emf - nodeVoltage) / rInt;

        if (netCurrent > CURRENT_EPSILON) {
            // Discharging into the grid. Current-limit clamp: a real source delivers what it
            // has (up to its rated current) with the terminal voltage sagging — it never
            // sources an unbounded Norton current into a stiff rail.
            double maxI = Math.max(0.0, source.getMaxOutputCurrent());
            if (Double.isFinite(maxI) && maxI > 0.0) {
                netCurrent = Math.min(netCurrent, maxI);
            }
            source.onPowerDrawn(netCurrent, TICK_DELTA_SECONDS);
            return nodeVoltage * netCurrent;
        }

        if (netCurrent < -CURRENT_EPSILON) {
            // Current flowing into the source (charging). Deliberately NOT clamped: an
            // overvoltage/AC fault must drive whatever Ohm's law dictates so Joule heating
            // and thermal-runaway protection still trigger. Regulated current limiting lives
            // in the converter output stage, not here.
            if (source instanceof IElectricStorage storage) {
                storage.onPowerReceived(nodeVoltage, -netCurrent, TICK_DELTA_SECONDS, this.gridFrequency);
            } else {
                source.onPowerDrawn(0.0, TICK_DELTA_SECONDS);
            }
            return 0.0;
        }

        // Zero net current: open circuit or balanced potential.
        source.onPowerDrawn(0.0, TICK_DELTA_SECONDS);
        return 0.0;
    }

    /** Applies block-breaking and fire spawns computed during the solve, safely after all physics is done. */
    private static void applyWorldHazards(ServerWorld world, WorldHazards hazards) {
        if (world == null) {
            return;
        }
        for (BlockPos p : hazards.blocksToBreak) {
            world.breakBlock(p, false);
        }
        for (BlockPos firePos : hazards.firesToSpawn) {
            if (world.isAir(firePos)) {
                world.setBlockState(firePos, Blocks.FIRE.getDefaultState());
            }
        }
    }

    /** Deferred physical side-effects collected during a tick, applied only after all calculations complete. */
    private static final class WorldHazards {
        final Set<BlockPos> blocksToBreak = new LinkedHashSet<>();
        final Set<BlockPos> firesToSpawn = new LinkedHashSet<>();
    }

    // ---------------------------------------------------------------------
    // Circuit assembly
    // ---------------------------------------------------------------------

    /**
     * Stamps grounds, conductors, consumer loads, and source equivalents into an MNA circuit.
     * Sources present in {@code currentOverrides} (identity-keyed) are stamped as ideal
     * current sources pushing the mapped amperage with open-circuit impedance instead of
     * their Thevenin equivalent; pass {@code null} or an empty map for a pure Thevenin build.
     * Any node left with no stamp at all (isolated by removals, or never wired to anything
     * live) is tied to ground through a very high safety resistance, so the conductance
     * matrix never carries an all-zero row that would make the solve singular.
     */
    private ModifiedNodalAnalysis.Circuit buildCircuit(
        List<BlockPos> posIndexList,
        Map<BlockPos, Integer> posToMnaNode,
        List<GridConductor> conductorList,
        Map<BlockPos, List<IElectricConsumer>> consumerSnapshot,
        Map<BlockPos, List<IElectricSource>> sourceSnapshot,
        Map<IElectricSource, Double> currentOverrides,
        Set<IElectricSource> openSources
    ) {
        int nodeCount = posIndexList.size();
        ModifiedNodalAnalysis.Circuit circuit = new ModifiedNodalAnalysis.Circuit(nodeCount);
        boolean[] stamped = new boolean[nodeCount + 1];

        stampGrounds(circuit, posIndexList, posToMnaNode, stamped);
        stampConductors(circuit, conductorList, posToMnaNode, stamped);
        stampConsumerLoads(circuit, consumerSnapshot, posToMnaNode, stamped);
        stampSources(circuit, sourceSnapshot, posToMnaNode, currentOverrides, openSources, stamped);
        stampFloatingNodeSafety(circuit, nodeCount, stamped);

        return circuit;
    }

    private void stampGrounds(
        ModifiedNodalAnalysis.Circuit circuit,
        List<BlockPos> posIndexList,
        Map<BlockPos, Integer> posToMnaNode,
        boolean[] stamped
    ) {
        for (BlockPos p : posIndexList) {
            GridNode node = nodes.get(p);
            if (node != null && node.isGround()) {
                int idx = posToMnaNode.get(p);
                circuit.addResistor(idx, 0, GROUND_TIE_RESISTANCE);
                stamped[idx] = true;
            }
        }
    }

    private static void stampConductors(
        ModifiedNodalAnalysis.Circuit circuit,
        List<GridConductor> conductorList,
        Map<BlockPos, Integer> posToMnaNode,
        boolean[] stamped
    ) {
        for (GridConductor c : conductorList) {
            Integer nodeA = posToMnaNode.get(c.getStartPos());
            Integer nodeB = posToMnaNode.get(c.getEndPos());
            if (nodeA != null && nodeB != null) {
                circuit.addResistor(nodeA, nodeB, c.getEffectiveResistance());
                stamped[nodeA] = true;
                stamped[nodeB] = true;
            }
        }
    }

    private static void stampConsumerLoads(
        ModifiedNodalAnalysis.Circuit circuit,
        Map<BlockPos, List<IElectricConsumer>> consumerSnapshot,
        Map<BlockPos, Integer> posToMnaNode,
        boolean[] stamped
    ) {
        for (Map.Entry<BlockPos, List<IElectricConsumer>> entry : consumerSnapshot.entrySet()) {
            Integer nodeIdx = posToMnaNode.get(entry.getKey());
            if (nodeIdx == null) {
                continue;
            }
            for (IElectricConsumer consumer : entry.getValue()) {
                double rLoad = consumer.getEquivalentResistance();
                if (Double.isFinite(rLoad) && rLoad > 0.0) {
                    circuit.addResistor(nodeIdx, 0, rLoad);
                    stamped[nodeIdx] = true;
                }
            }
        }
    }

    private static void stampSources(
        ModifiedNodalAnalysis.Circuit circuit,
        Map<BlockPos, List<IElectricSource>> sourceSnapshot,
        Map<BlockPos, Integer> posToMnaNode,
        Map<IElectricSource, Double> currentOverrides,
        Set<IElectricSource> openSources,
        boolean[] stamped
    ) {
        for (Map.Entry<BlockPos, List<IElectricSource>> entry : sourceSnapshot.entrySet()) {
            Integer nodeIdx = posToMnaNode.get(entry.getKey());
            if (nodeIdx == null) {
                continue;
            }
            for (IElectricSource src : entry.getValue()) {
                if (openSources != null && openSources.contains(src)) {
                    // Reverse-blocked (output diode): open circuit, draws nothing. Deliberately
                    // NOT stamped here — an open source contributes no conductance of its own;
                    // the floating-node safety net (or another consumer/conductor) covers it.
                    continue;
                }

                double emf = src.getElectromotiveForce();
                if (emf <= 0.0) {
                    // A source with EMF <= 0 (disabled converter output, night solar, discharged
                    // battery) is high impedance / open circuit, NOT a shunt to ground. Stamping
                    // its small Rint here would short the grid through a ~0.05-ohm path and
                    // collapse rails that are actually floating or held up by other sources.
                    continue;
                }

                Double forced = currentOverrides != null ? currentOverrides.get(src) : null;
                if (forced != null) {
                    circuit.addCurrentSource(0, nodeIdx, Math.max(0.0, forced));
                } else {
                    double rInt = Math.max(1e-4, src.getInternalResistance());
                    circuit.addCurrentSource(0, nodeIdx, emf / rInt);
                    circuit.addResistor(nodeIdx, 0, rInt);
                }
                stamped[nodeIdx] = true;
            }
        }
    }

    /** Ties any still-unconnected node to ground through a large safety resistance to keep the matrix solvable. */
    private static void stampFloatingNodeSafety(ModifiedNodalAnalysis.Circuit circuit, int nodeCount, boolean[] stamped) {
        for (int idx = 1; idx <= nodeCount; idx++) {
            if (!stamped[idx]) {
                circuit.addResistor(idx, 0, FLOATING_NODE_SAFETY_RESISTANCE);
            }
        }
    }
}