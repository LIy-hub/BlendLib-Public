package com.liy.blendlib.fabric.v262.resource;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.core.loader.ModelAssetLoader;
import com.liy.blendlib.fabric.v262.diagnostic.Fabric262Diagnostic;
import com.liy.blendlib.fabric.v262.diagnostic.Fabric262DiagnosticCode;
import com.liy.blendlib.fabric.v262.diagnostic.Fabric262PlatformException;
import com.liy.blendlib.fabric.v262.model.Fabric262FrameState;
import com.liy.blendlib.fabric.v262.model.Fabric262PreparedModelHandle;
import com.liy.blendlib.fabric.v262.model.Fabric262RenderSnapshot;
import com.liy.blendlib.fabric.v262.model.Fabric262ResourceGeneration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns the Fabric 26.2 strict-resource reload, publish, and snapshot lifecycle.
 *
 * <p><strong>Platform target:</strong> Fabric on Minecraft 26.2. Discovery, descriptor/GLB reads,
 * and pure core decoding occur only in {@link #prepare(Fabric262ResourceAccess)}. Application uses
 * one atomic publication. The submit-facing {@link #snapshot(BlendModelKey, Fabric262FrameState)}
 * path never accesses resource I/O, JSON, GLB parsing, or capability/provider discovery.</p>
 */
public final class Fabric262ResourceReloadCoordinator implements AutoCloseable {
    private final ModelAssetLoader loader;
    private final AtomicLong generationCounter = new AtomicLong();
    private final AtomicReference<Fabric262ResourceGeneration> active = new AtomicReference<>(
            new Fabric262ResourceGeneration(0L, java.util.Map.of(), List.of()));
    private final Object lifecycleMonitor = new Object();
    private boolean closed;

    /**
     * Creates a coordinator backed by the standard strict pure-Java model loader.
     */
    public Fabric262ResourceReloadCoordinator() {
        this(new ModelAssetLoader());
    }

    /**
     * Creates a coordinator with an explicit strict pure-Java loader.
     *
     * @param loader pure core loader; it must not depend on Minecraft/Fabric types
     */
    public Fabric262ResourceReloadCoordinator(ModelAssetLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    /**
     * Reads and decodes every discovered strict descriptor/GLB pair into an isolated candidate.
     *
     * <p>A broken individual asset is converted to its named diagnostic fallback so the remaining
     * valid models can still publish. Java {@link Error} values are not converted and propagate
     * unchanged; only expected adapter/core runtime failures receive a fallback.</p>
     *
     * @param resources reload-scoped Fabric resource access
     * @return immutable, not-yet-active reload plan
     */
    public Fabric262ReloadPlan prepare(Fabric262ResourceAccess resources) {
        Fabric262ResourceAccess checkedResources = Objects.requireNonNull(resources, "resources");
        requireOpen();
        long generation = generationCounter.incrementAndGet();
        LinkedHashMap<BlendModelKey, Fabric262PreparedModelHandle> handles = new LinkedHashMap<>();
        List<Fabric262Diagnostic> diagnostics = new ArrayList<>();
        for (BlendModelKey key : checkedResources.discoverModels()) {
            try {
                var asset = loader.load(
                        key.resourceId(), generation, checkedResources.descriptor(key), checkedResources);
                handles.put(key, Fabric262PreparedModelHandle.prepare(asset));
            } catch (RuntimeException exception) {
                Fabric262Diagnostic diagnostic = diagnosticFor(key, exception);
                diagnostics.add(diagnostic);
                handles.put(key, Fabric262PreparedModelHandle.missing(key, generation, diagnostic));
            }
        }
        return new Fabric262ReloadPlan(generation, handles, diagnostics);
    }

    /**
     * Atomically publishes a prepared plan and retires only the preceding generation.
     *
     * @param plan immutable payload returned by {@link #prepare(Fabric262ResourceAccess)}
     * @return newly active immutable generation
     */
    public Fabric262ResourceGeneration apply(Fabric262ReloadPlan plan) {
        Fabric262ReloadPlan checkedPlan = Objects.requireNonNull(plan, "plan");
        Fabric262ResourceGeneration published = new Fabric262ResourceGeneration(
                checkedPlan.generation(), checkedPlan.handles(), checkedPlan.diagnostics());
        Fabric262ResourceGeneration previous;
        synchronized (lifecycleMonitor) {
            requireOpenLocked();
            previous = active.get();
            if (checkedPlan.generation() <= previous.generation()) {
                throw new Fabric262PlatformException(Fabric262Diagnostic.error(
                        Fabric262DiagnosticCode.STALE_RELOAD_PLAN,
                        "Fabric 26.2 reload plan generation " + checkedPlan.generation()
                                + " is not newer than active generation " + previous.generation()));
            }
            // Preserve the monitor -> generation lock order used by snapshot(). No publicly held
            // former generation can acquire a new snapshot once publication has completed.
            previous.retire();
            active.set(published);
        }
        return published;
    }

    /**
     * Prepares and applies one listener-owned reload while treating a terminal close as a no-op.
     *
     * <p>This convenience boundary preserves the explicit public {@link #prepare(Fabric262ResourceAccess)}
     * and {@link #apply(Fabric262ReloadPlan)} phases for lifecycle tooling. It exists so the
     * permanently registered Fabric listener cannot republish a generation after this runtime has
     * closed and intentionally become terminal.</p>
     *
     * @param resources reload-scoped Fabric resource access
     */
    public void reload(Fabric262ResourceAccess resources) {
        try {
            apply(prepare(resources));
        } catch (Fabric262PlatformException exception) {
            if (exception.diagnostic().code() != Fabric262DiagnosticCode.RUNTIME_CLOSED) {
                throw exception;
            }
        }
    }

    /**
     * Captures one generation-pinned immutable render snapshot from the active generation.
     *
     * @param modelKey strict semantic model key
     * @param frame immutable caller-provided render state
     * @return independently closable prepared snapshot
     */
    public Fabric262RenderSnapshot snapshot(BlendModelKey modelKey, Fabric262FrameState frame) {
        synchronized (lifecycleMonitor) {
            requireOpenLocked();
            return active.get().snapshot(
                    Objects.requireNonNull(modelKey, "modelKey"), Objects.requireNonNull(frame, "frame"));
        }
    }

    /**
     * Returns the current immutable generation for diagnostics and lifecycle inspection.
     *
     * @return active generation snapshot, or a retired terminal tombstone after close
     */
    public Fabric262ResourceGeneration activeGeneration() {
        return active.get();
    }

    /**
     * Retires the current generation and rejects future adapter-owned publication by convention.
     *
     * <p>Existing snapshots remain valid until their callers close them. This method releases no
     * raw GPU resource because the candidate uses copied CPU/standard collector data only.</p>
     */
    @Override
    public void close() {
        synchronized (lifecycleMonitor) {
            if (closed) {
                return;
            }
            closed = true;
            Fabric262ResourceGeneration terminalTombstone = new Fabric262ResourceGeneration(
                    generationCounter.incrementAndGet(), java.util.Map.of(), List.of());
            terminalTombstone.retire();
            active.get().retire();
            active.set(terminalTombstone);
        }
    }

    private void requireOpen() {
        synchronized (lifecycleMonitor) {
            requireOpenLocked();
        }
    }

    private void requireOpenLocked() {
        if (closed) {
            throw new Fabric262PlatformException(Fabric262Diagnostic.error(
                    Fabric262DiagnosticCode.RUNTIME_CLOSED,
                    "Fabric 26.2 resource coordinator is terminal"));
        }
    }

    private static Fabric262Diagnostic diagnosticFor(BlendModelKey key, RuntimeException exception) {
        if (exception instanceof Fabric262PlatformException platformException) {
            Fabric262Diagnostic source = platformException.diagnostic();
            return Fabric262Diagnostic.error(source.code(), key, source.message());
        }
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception.getClass().getSimpleName();
        }
        Fabric262DiagnosticCode code = message.contains("requires rigid_v1")
                || message.contains("standard submit")
                || message.contains("materials")
                ? Fabric262DiagnosticCode.UNSUPPORTED_RENDER_PROFILE
                : Fabric262DiagnosticCode.RESOURCE_LOAD_FAILURE;
        return Fabric262Diagnostic.error(code, key, "Reload preparation failed: " + bounded(message));
    }

    private static String bounded(String message) {
        return message.length() <= 400 ? message : message.substring(0, 397) + "...";
    }
}
