package com.ostapyrih.voltcraft.simulation.grid;

import com.ostapyrih.voltcraft.api.electrical.Conductor;
import com.ostapyrih.voltcraft.api.electrical.ElectricalElement;
import com.ostapyrih.voltcraft.api.grid.KernelAttachedBlock;
import net.minecraft.util.math.BlockPos;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Structural snapshot of one electrically isolated island.
 *
 * <p>Shape (island contract): {@link #kernel}, {@link #elementIndex}
 * ({@code BlockPos -> int}, keyed by block-entity position, sorted by
 * {@code BlockPos} so indices are deterministic regardless of discovery order),
 * {@link #nodeIndex} ({@code BlockPos -> int}, sorted node positions),
 * {@link #blocks}, {@link #elements}, {@link #terminalIndices} (per-element
 * kernel node indices), {@link #conductors}, {@link #omega}, plus
 * {@link #nodeCount} and the mutable {@link #fallbackActive} flag observed
 * after each tick.</p>
 */
public final class IslandContext {
    private final ElectricalKernel kernel;
    private final Map<BlockPos, Integer> elementIndex;
    private final Map<BlockPos, Integer> nodeIndex;
    private final List<KernelAttachedBlock> blocks;
    private final List<ElectricalElement> elements;
    private final List<int[]> terminalIndices;
    private final List<Conductor> conductors;
    private final double omega;
    private final int nodeCount;
    private boolean fallbackActive;

    public IslandContext(
        ElectricalKernel kernel,
        Map<BlockPos, Integer> elementIndex,
        Map<BlockPos, Integer> nodeIndex,
        List<KernelAttachedBlock> blocks,
        List<ElectricalElement> elements,
        List<int[]> terminalIndices,
        List<Conductor> conductors,
        double omega,
        int nodeCount,
        boolean fallbackActive
    ) {
        this.kernel = kernel;
        this.elementIndex = Collections.unmodifiableMap(elementIndex);
        this.nodeIndex = Collections.unmodifiableMap(nodeIndex);
        this.blocks = Collections.unmodifiableList(blocks);
        this.elements = Collections.unmodifiableList(elements);
        List<int[]> terminalCopy = new java.util.ArrayList<>(terminalIndices.size());
        for (int[] t : terminalIndices) {
            terminalCopy.add(t.clone());
        }
        this.terminalIndices = Collections.unmodifiableList(terminalCopy);
        this.conductors = Collections.unmodifiableList(conductors);
        this.omega = omega;
        this.nodeCount = nodeCount;
        this.fallbackActive = fallbackActive;
    }

    public ElectricalKernel kernel() {
        return kernel;
    }

    public Map<BlockPos, Integer> elementIndex() {
        return elementIndex;
    }

    public Map<BlockPos, Integer> nodeIndex() {
        return nodeIndex;
    }

    public List<KernelAttachedBlock> blocks() {
        return blocks;
    }

    public List<ElectricalElement> elements() {
        return elements;
    }

    public List<int[]> terminalIndices() {
        return terminalIndices;
    }

    public List<Conductor> conductors() {
        return conductors;
    }

    public double omega() {
        return omega;
    }

    public int nodeCount() {
        return nodeCount;
    }

    public boolean fallbackActive() {
        return fallbackActive;
    }

    public void setFallbackActive(boolean fallbackActive) {
        this.fallbackActive = fallbackActive;
    }
}
