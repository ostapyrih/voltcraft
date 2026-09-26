package com.ostapyrih.voltcraft.simulation.grid;

import com.ostapyrih.voltcraft.api.data.ElectricalState;
import com.ostapyrih.voltcraft.api.energy.IElectricConsumer;
import com.ostapyrih.voltcraft.api.energy.IElectricSource;
import com.ostapyrih.voltcraft.api.energy.IElectricStorage;
import com.ostapyrih.voltcraft.api.grid.IElectricalGrid;
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
 */
public class ElectricalGrid implements IElectricalGrid {

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
        List<IElectricSource> list = sources.computeIfAbsent(pos.toImmutable(), p -> new ArrayList<>());
        if (!list.contains(source)) {
            list.add(source);
        }
    }

    public void unregisterSource(BlockPos pos) {
        sources.remove(pos);
    }

    public void unregisterSource(BlockPos pos, IElectricSource source) {
        List<IElectricSource> list = sources.get(pos);
        if (list != null) {
            list.remove(source);
            if (list.isEmpty()) {
                sources.remove(pos);
            }
        }
    }

    public void unregisterSource(IElectricSource source) {
        for (Iterator<Map.Entry<BlockPos, List<IElectricSource>>> it = sources.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<BlockPos, List<IElectricSource>> entry = it.next();
            entry.getValue().remove(source);
            if (entry.getValue().isEmpty()) {
                it.remove();
            }
        }
    }

    public void registerConsumer(BlockPos pos, IElectricConsumer consumer) {
        addNode(pos, false);
        List<IElectricConsumer> list = consumers.computeIfAbsent(pos.toImmutable(), p -> new ArrayList<>());
        if (!list.contains(consumer)) {
            list.add(consumer);
        }
    }

    public void unregisterConsumer(BlockPos pos) {
        consumers.remove(pos);
    }

    public void unregisterConsumer(BlockPos pos, IElectricConsumer consumer) {
        List<IElectricConsumer> list = consumers.get(pos);
        if (list != null) {
            list.remove(consumer);
            if (list.isEmpty()) {
                consumers.remove(pos);
            }
        }
    }

    public void unregisterConsumer(IElectricConsumer consumer) {
        for (Iterator<Map.Entry<BlockPos, List<IElectricConsumer>>> it = consumers.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<BlockPos, List<IElectricConsumer>> entry = it.next();
            entry.getValue().remove(consumer);
            if (entry.getValue().isEmpty()) {
                it.remove();
            }
        }
    }

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

    @Override
    public void tick(ServerWorld world) {
        if (nodes.isEmpty()) {
            return;
        }

        // Auto-prune any consumers or sources whose underlying BlockEntity has been marked removed
        consumers.values().forEach(list -> list.removeIf(c -> {
            if (c instanceof BlockEntity be && be.isRemoved()) return true;
            if (c instanceof AbstractPowerConverterBlockEntity.InputConsumer ic && ic.isRemoved()) return true;
            return false;
        }));
        consumers.entrySet().removeIf(entry -> entry.getValue().isEmpty());

        sources.values().forEach(list -> list.removeIf(s -> {
            if (s instanceof BlockEntity be && be.isRemoved()) return true;
            if (s instanceof AbstractPowerConverterBlockEntity.OutputSource os && os.isRemoved()) return true;
            return false;
        }));
        sources.entrySet().removeIf(entry -> entry.getValue().isEmpty());

        // Map discrete positions to sequential 1-based integer indices for MNA matrix.
        // Node 0 is reserved as common Ground reference (0.0V).
        List<BlockPos> posIndexList = new ArrayList<>(nodes.keySet());
        Map<BlockPos, Integer> posToMnaNode = new HashMap<>();

        for (int i = 0; i < posIndexList.size(); i++) {
            posToMnaNode.put(posIndexList.get(i), i + 1);
        }

        // Snapshots for stable iteration while block entities register/unregister mid-tick.
        List<GridConductor> conductorList = new ArrayList<>(this.conductors);

        Map<BlockPos, List<IElectricConsumer>> consumerSnapshot = new HashMap<>();
        for (Map.Entry<BlockPos, List<IElectricConsumer>> entry : consumers.entrySet()) {
            consumerSnapshot.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }

        Map<BlockPos, List<IElectricSource>> sourceSnapshot = new HashMap<>();
        for (Map.Entry<BlockPos, List<IElectricSource>> entry : sources.entrySet()) {
            sourceSnapshot.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }

        // Determine dominant operating AC frequency across the grid (if driven by AC sources)
        double dominantAcFreq = 0.0;
        double maxAcEmf = 0.0;
        for (List<IElectricSource> srcList : sourceSnapshot.values()) {
            for (IElectricSource src : srcList) {
                if (src.getElectromotiveForce() > 0.5 && src.getFrequency() > 0.001) {
                    if (src.getElectromotiveForce() > maxAcEmf) {
                        maxAcEmf = src.getElectromotiveForce();
                        dominantAcFreq = src.getFrequency();
                    }
                }
            }
        }
        this.gridFrequency = dominantAcFreq;

        // Pass 1: stamp everything as Thevenin equivalents and solve.
        ModifiedNodalAnalysis.Circuit circuit = buildCircuit(
            posIndexList, posToMnaNode, conductorList, consumerSnapshot, sourceSnapshot, null, null);
        ModifiedNodalAnalysis.Solution solution = ModifiedNodalAnalysis.solve(circuit);

        // Pass 2 (corrector): non-storage sources (converters, solar) whose pass-1
        // current exceeds what they can sustain are re-stamped as ideal current
        // sources pushing exactly their available current; non-storage sources with
        // pass-1 REVERSE current are re-stamped as open circuits (output diode
        // blocks: a floated charger must neither source nor sink). The network is
        // then solved once more. Storage keeps Thevenin behavior so fault currents
        // (shorts, surges) still flow per Ohm's law. Stateless by design: pass 1
        // acts purely as a detector, so there is no latch to chatter, strobe, or
        // deadlock — a static network produces the identical corrected result tick.
        Map<IElectricSource, Double> currentOverrides = new IdentityHashMap<>();
        Set<IElectricSource> openSources = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Map.Entry<BlockPos, List<IElectricSource>> entry : sourceSnapshot.entrySet()) {
            Integer nodeIdx = posToMnaNode.get(entry.getKey());
            if (nodeIdx == null) {
                continue;
            }
            double nodeVoltage = solution.getNodeVoltage(nodeIdx);
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
                if (pass1Current < -1e-4) {
                    openSources.add(src);
                    continue;
                }
                double avail = src.getAvailableOutputCurrent();
                if (Double.isFinite(avail) && avail <= 1e-4) {
                    openSources.add(src);
                    continue;
                }
                boolean isCurrentRegulated = (src instanceof AbstractPowerConverterBlockEntity.OutputSource os
                    && os.isCurrentRegulated());
                // Engage corrector pass if Pass 1 current exceeds available capacity,
                // or if source operates in constant-current injection mode (e.g. MPPT bulk charger).
                // 2% deadband prevents numerical noise chatter while immediately clamping overloads
                // and delivering exact constant available current without voltage oscillation.
                if ((isCurrentRegulated || pass1Current > avail * 1.02) && Double.isFinite(avail) && avail > 1e-4
                    && avail < emf / rInt) {
                    currentOverrides.put(src, avail);
                }
            }
        }
        if (!currentOverrides.isEmpty() || !openSources.isEmpty()) {
            circuit = buildCircuit(posIndexList, posToMnaNode, conductorList,
                consumerSnapshot, sourceSnapshot, currentOverrides, openSources);
            solution = ModifiedNodalAnalysis.solve(circuit);
        }

        // 5. Update nodal voltages back into GridNode instances
        for (BlockPos p : posIndexList) {
            int mnaNode = posToMnaNode.get(p);
            double v = solution.getNodeVoltage(mnaNode);
            GridNode node = nodes.get(p);
            if (node != null) {
                node.setVoltage(v);
            }
        }

        // 6. Update conductor currents and thermal physics (0.05 seconds per tick)
        double dtSeconds = 0.05;
        double ambient = 20.0;
        this.totalConsumptionWatts = 0.0;
        this.totalGenerationWatts = 0.0;

        Set<BlockPos> blocksToBreak = new LinkedHashSet<>();
        Set<BlockPos> firesToSpawn = new LinkedHashSet<>();
        List<GridConductor> burnedConductors = new ArrayList<>();

        for (GridConductor c : conductorList) {
            double vA = nodes.get(c.getStartPos()) != null ? nodes.get(c.getStartPos()).getVoltage() : 0.0;
            double vB = nodes.get(c.getEndPos()) != null ? nodes.get(c.getEndPos()).getVoltage() : 0.0;
            double deltaV = Math.abs(vA - vB);
            double current = deltaV / Math.max(1e-6, c.getEffectiveResistance());
            c.setCurrent(current);

            c.updateThermal(ambient, dtSeconds);

            // Thermal hazard check: melt metal or insulation
            if (c.getTemperature() >= c.getMeltingTemp()) {
                burnedConductors.add(c);
                BlockPos breakTarget = c.getStartPos();
                if (world != null) {
                    if (world.getBlockState(c.getStartPos()).getBlock() instanceof com.ostapyrih.voltcraft.block.cable.CableBlock) {
                        breakTarget = c.getStartPos();
                    } else if (world.getBlockState(c.getEndPos()).getBlock() instanceof com.ostapyrih.voltcraft.block.cable.CableBlock) {
                        breakTarget = c.getEndPos();
                    }
                }
                blocksToBreak.add(breakTarget);
                firesToSpawn.add(breakTarget);
            } else if (c.isInsulated() && c.getTemperature() >= c.getMaxInsulationTemp()) {
                // If touching flammable block, spawn fire
                firesToSpawn.add(c.getStartPos().up());
            }
        }

        if (!burnedConductors.isEmpty()) {
            this.conductors.removeAll(burnedConductors);
        }

        // 7. Dispatch power to consumers and update state machine
        for (Map.Entry<BlockPos, List<IElectricConsumer>> entry : consumerSnapshot.entrySet()) {
            GridNode node = nodes.get(entry.getKey());
            double terminalV = node != null ? node.getVoltage() : 0.0;
            for (IElectricConsumer consumer : entry.getValue()) {
                double deliveredI = terminalV / Math.max(1e-6, consumer.getEquivalentResistance());
                double deliveredWatts = terminalV * deliveredI;
                this.totalConsumptionWatts += deliveredWatts;

                consumer.onPowerReceived(terminalV, deliveredI, dtSeconds, this.gridFrequency);

                if (terminalV < consumer.getMinOperatingVoltage()) {
                    consumer.setElectricalState(terminalV <= 0.1 ? ElectricalState.OFF : ElectricalState.BROWNOUT);
                } else if (terminalV > consumer.getMaxOperatingVoltage() * 1.3) {
                    consumer.setElectricalState(ElectricalState.DESTROYED);
                } else if (terminalV > consumer.getMaxOperatingVoltage()) {
                    consumer.setElectricalState(ElectricalState.SURGE);
                } else {
                    consumer.setElectricalState(ElectricalState.NOMINAL);
                }
            }
        }

        // 8. Update sources based on actual net current delivered to the grid
        for (Map.Entry<BlockPos, List<IElectricSource>> entry : sourceSnapshot.entrySet()) {
            GridNode node = nodes.get(entry.getKey());
            double v = node != null ? node.getVoltage() : 0.0;
            for (IElectricSource source : entry.getValue()) {
                double emf = source.getElectromotiveForce();
                double rInt = Math.max(1e-4, source.getInternalResistance());
                if (openSources.contains(source)) {
                    // Reverse-blocked in the corrected solve: delivers nothing.
                    source.onPowerDrawn(0.0, dtSeconds);
                    continue;
                }
                Double forced = currentOverrides.get(source);
                if (forced != null) {
                    // Corrector pass stamped this source as an ideal current source:
                    // it forced exactly `forced` amps into the node. Reverse-blocked
                    // (output diode) if the node floats above EMF, e.g. a full
                    // battery holding the rail over a floated charger setpoint.
                    double delivered = (v > emf) ? 0.0 : Math.max(0.0, forced);
                    if (delivered > 1e-4) {
                        this.totalGenerationWatts += v * delivered;
                        source.onPowerDrawn(delivered, dtSeconds);
                    } else {
                        source.onPowerDrawn(0.0, dtSeconds);
                    }
                    continue;
                }

                double netCurrent = (emf - v) / rInt;
                // Current-limit clamp: a real source delivers what it has (up to its
                // rated current) with the terminal voltage sagging, it never sinks or
                // sources an unbounded MNA Norton current into a stiff rail.
                double maxI = Math.max(0.0, source.getMaxOutputCurrent());

                if (netCurrent > 1e-4) {
                    // Source is discharging into the grid
                    if (Double.isFinite(maxI) && maxI > 0.0) {
                        netCurrent = Math.min(netCurrent, maxI);
                    }
                    this.totalGenerationWatts += v * netCurrent;
                    source.onPowerDrawn(netCurrent, dtSeconds);
                } else if (netCurrent < -1e-4) {
                    // Current is flowing into the source (charging). Fault current
                    // is deliberately NOT clamped: an overvoltage/AC fault must drive
                    // whatever Ohm's law dictates so Joule heating and thermal
                    // runaway protection still trigger. Regulated current-limiting
                    // lives in the converter output stage, not here.
                    double chargeI = -netCurrent;
                    if (source instanceof IElectricStorage storage) {
                        storage.onPowerReceived(v, chargeI, dtSeconds, this.gridFrequency);
                    } else {
                        source.onPowerDrawn(0.0, dtSeconds);
                    }
                } else {
                    // Zero net current (open circuit or balanced potential)
                    source.onPowerDrawn(0.0, dtSeconds);
                }
            }
        }

        this.stable = true;

        // 9. Execute deferred physical world side-effects safely AFTER all calculations and loops complete
        if (world != null) {
            for (BlockPos p : blocksToBreak) {
                world.breakBlock(p, false);
            }
            for (BlockPos firePos : firesToSpawn) {
                if (world.isAir(firePos)) {
                    world.setBlockState(firePos, Blocks.FIRE.getDefaultState());
                }
            }
        }
    }

    /**
     * Stamps grounds, conductors, consumer loads, and source equivalents into an MNA circuit.
     * Sources present in {@code currentOverrides} (identity-keyed) are stamped as ideal
     * current sources pushing the mapped amperage with open-circuit impedance instead of
     * their Thevenin equivalent; pass {@code null} or an empty map for a pure Thevenin build.
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
        ModifiedNodalAnalysis.Circuit circuit = new ModifiedNodalAnalysis.Circuit(posIndexList.size());

        // 0. Explicit ground nodes tied to Node 0 (Earth Ground)
        for (BlockPos p : posIndexList) {
            GridNode node = nodes.get(p);
            if (node != null && node.isGround()) {
                circuit.addResistor(posToMnaNode.get(p), 0, 1e-4);
            }
        }

        // 1. Stamp Conductors into circuit (iterate on snapshot)
        for (GridConductor c : conductorList) {
            Integer nodeA = posToMnaNode.get(c.getStartPos());
            Integer nodeB = posToMnaNode.get(c.getEndPos());
            if (nodeA != null && nodeB != null) {
                circuit.addResistor(nodeA, nodeB, c.getEffectiveResistance());
            }
        }

        // 2. Stamp Consumers as equivalent resistive loads to ground (snapshot)
        for (Map.Entry<BlockPos, List<IElectricConsumer>> entry : consumerSnapshot.entrySet()) {
            Integer nodeIdx = posToMnaNode.get(entry.getKey());
            if (nodeIdx != null) {
                for (IElectricConsumer consumer : entry.getValue()) {
                    double rLoad = consumer.getEquivalentResistance();
                    if (Double.isFinite(rLoad) && rLoad > 0.0) {
                        circuit.addResistor(nodeIdx, 0, rLoad);
                    }
                }
            }
        }

        // 3. Stamp Sources as Norton equivalents (snapshot)
        for (Map.Entry<BlockPos, List<IElectricSource>> entry : sourceSnapshot.entrySet()) {
            Integer nodeIdx = posToMnaNode.get(entry.getKey());
            if (nodeIdx != null) {
                for (IElectricSource src : entry.getValue()) {
                    if (openSources != null && openSources.contains(src)) {
                        // Reverse-blocked (output diode): open circuit, draws nothing.
                        continue;
                    }
                    double emf = src.getElectromotiveForce();
                    double rInt = Math.max(1e-4, src.getInternalResistance());
                    if (emf > 0.0) {
                        Double forced = currentOverrides != null ? currentOverrides.get(src) : null;
                        if (forced != null) {
                            circuit.addCurrentSource(0, nodeIdx, Math.max(0.0, forced));
                        } else {
                            double iNorton = emf / rInt;
                            circuit.addCurrentSource(0, nodeIdx, iNorton);
                            circuit.addResistor(nodeIdx, 0, rInt);
                        }
                    }
                    // Note: a source with EMF <= 0 (disabled converter output, night
                    // solar, discharged/dead battery) is high impedance / open circuit,
                    // NOT a shunt to ground. Stamping its small Rint here would short
                    // the grid through a ~0.05-ohm path and collapse rails that are
                    // actually floating or held up by other sources.
                }
            }
        }
        return circuit;
    }
}
