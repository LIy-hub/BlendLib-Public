package com.liy.blendlib.fabric.v262.model;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.fabric.v262.diagnostic.Fabric262Diagnostic;
import com.liy.blendlib.fabric.v262.diagnostic.Fabric262DiagnosticCode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Immutable published Fabric 26.2 resource generation with explicit snapshot leases.
 *
 * <p><strong>Platform target:</strong> Fabric on Minecraft 26.2. Maps and diagnostics are copied
 * before publication. Reload replacement retires a generation, while snapshots retain an exact
 * pin until closed; no mutable core asset or platform resource manager escapes this boundary.</p>
 */
public final class Fabric262ResourceGeneration {
    private final long generation;
    private final Map<BlendModelKey, Fabric262PreparedModelHandle> handles;
    private final List<Fabric262Diagnostic> diagnostics;
    private final AtomicBoolean retired = new AtomicBoolean();
    private final AtomicInteger snapshotPins = new AtomicInteger();

    /**
     * Creates an immutable resource generation from reload-prepared payloads.
     *
     * @param generation non-negative reload generation
     * @param handles strict key to immutable prepared handle mapping
     * @param diagnostics immutable adapter diagnostics
     */
    public Fabric262ResourceGeneration(
            long generation,
            Map<BlendModelKey, Fabric262PreparedModelHandle> handles,
            List<Fabric262Diagnostic> diagnostics) {
        if (generation < 0L) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
        this.generation = generation;
        Objects.requireNonNull(handles, "handles");
        LinkedHashMap<BlendModelKey, Fabric262PreparedModelHandle> ordered = new LinkedHashMap<>();
        handles.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(BlendModelKey::value)))
                .forEach(entry -> ordered.put(
                        Objects.requireNonNull(entry.getKey(), "handles key"),
                        Objects.requireNonNull(entry.getValue(), "handles value")));
        this.handles = Collections.unmodifiableMap(ordered);
        this.diagnostics = List.copyOf(new ArrayList<>(Objects.requireNonNull(diagnostics, "diagnostics")));
    }

    /**
     * Returns the reload generation number.
     *
     * @return non-negative generation number
     */
    public long generation() {
        return generation;
    }

    /**
     * Returns a deterministic immutable snapshot of reload diagnostics.
     *
     * @return immutable adapter diagnostics
     */
    public List<Fabric262Diagnostic> diagnostics() {
        return diagnostics;
    }

    /**
     * Returns the number of snapshots currently retaining this generation.
     *
     * @return non-negative active snapshot count
     */
    public int snapshotPins() {
        return snapshotPins.get();
    }

    /**
     * Reports whether this generation is retired from future snapshot acquisition.
     *
     * @return true after replacement or runtime close
     */
    public boolean isRetired() {
        return retired.get();
    }

    /**
     * Captures an exact prepared handle for one ordinary model submit.
     *
     * @param modelKey strict semantic model key
     * @param frame copied immutable render-time state
     * @return independently closable generation-pinned snapshot
     */
    public synchronized Fabric262RenderSnapshot snapshot(BlendModelKey modelKey, Fabric262FrameState frame) {
        BlendModelKey checkedKey = Objects.requireNonNull(modelKey, "modelKey");
        Fabric262FrameState checkedFrame = Objects.requireNonNull(frame, "frame");
        if (retired.get()) {
            throw new IllegalStateException("A retired Fabric 26.2 resource generation cannot issue snapshots");
        }
        snapshotPins.incrementAndGet();
        Fabric262PreparedModelHandle handle = handles.get(checkedKey);
        if (handle == null) {
            handle = Fabric262PreparedModelHandle.missing(checkedKey, generation, Fabric262Diagnostic.error(
                    Fabric262DiagnosticCode.MODEL_NOT_PUBLISHED, checkedKey,
                    "No prepared model exists for the requested key in generation " + generation));
        }
        return new Fabric262RenderSnapshot(this, handle, checkedFrame);
    }

    /**
     * Stops new snapshots while allowing already-created snapshots to drain exactly once.
     */
    public synchronized void retire() {
        retired.set(true);
    }

    synchronized void releaseSnapshot() {
        if (snapshotPins.get() <= 0) {
            throw new IllegalStateException("Fabric 26.2 snapshot pin count underflow");
        }
        snapshotPins.decrementAndGet();
    }
}
