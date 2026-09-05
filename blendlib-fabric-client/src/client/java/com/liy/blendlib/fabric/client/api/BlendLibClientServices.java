package com.liy.blendlib.fabric.client.api;

import com.liy.blendlib.fabric.client.command.ClientDiagnosticsCommandRegistry;
import com.liy.blendlib.fabric.client.command.ClientDiagnosticsService;
import com.liy.blendlib.fabric.client.animation.runtime.SkinnedAnimationRuntime;
import com.liy.blendlib.fabric.client.reload.ClientModelRegistry;
import com.liy.blendlib.fabric.client.render.ModelRenderBackend;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Client-only facade installed by the 26.1.2 client entrypoint.
 *
 * <p>The facade deliberately owns neither Minecraft's global client instance nor any core asset
 * state. The entrypoint supplies the generation registry and backend once; consumers receive only
 * read-only model inspection, snapshot-only submit, and diagnostic-command surfaces.</p>
 */
public final class BlendLibClientServices {
    private static final AtomicReference<Services> ACTIVE = new AtomicReference<>();
    private static final ClientModelLookupBootstrap MANAGED_LOOKUP_BOOTSTRAP = new ManagedLookupBootstrap();

    /** The sole Java-source constructible bootstrap token for a registry-backed managed lookup. */
    static final class ManagedLookupBootstrap implements ClientModelLookupBootstrap {
        private ManagedLookupBootstrap() {
        }
    }

    private BlendLibClientServices() {
    }

    /**
     * Installs the current adapter's services. This is an adapter-entrypoint operation, not a
     * consumer registration API. Repeating it with the same objects is harmless; replacing an
     * installed adapter is rejected.
     */
    public static void initialize(ClientModelRegistry registry, ModelRenderBackend backend) {
        install(registry, backend, null);
    }

    /**
     * Installs the current adapter services together with its single owner of P5 extraction-side
     * skinned instance state.
     *
     * <p>The runtime is supplied only by the client entrypoint. Entity adapters may use it while
     * extracting immutable snapshots, but it never becomes a renderer-submit dependency.</p>
     */
    public static void initialize(
            ClientModelRegistry registry, ModelRenderBackend backend, SkinnedAnimationRuntime skinnedAnimationRuntime) {
        install(registry, backend, Objects.requireNonNull(skinnedAnimationRuntime, "skinnedAnimationRuntime"));
    }

    /** Whether the client entrypoint installed this adapter's service facade. */
    public static boolean isInitialized() {
        return ACTIVE.get() != null;
    }

    /** Read-only current-generation model lookup. */
    public static ClientModelLookup models() {
        return active().models();
    }

    /** Snapshot-only low-level renderer. */
    public static BlendRenderer renderer() {
        return active().renderer();
    }

    /** Client diagnostics query service. */
    public static ClientDiagnosticsService diagnostics() {
        return active().diagnostics();
    }

    /** Command-neutral registry for {@code assets}, {@code inspect}, and {@code diagnostics}. */
    public static ClientDiagnosticsCommandRegistry commands() {
        return active().commands();
    }

    /**
     * Returns the entrypoint-owned P5 skinned extraction runtime.
     *
     * <p>This is an adapter integration seam, not a gameplay or network API. It may only be used
     * before rendering to prepare immutable snapshots.</p>
     */
    public static SkinnedAnimationRuntime skinnedAnimationRuntime() {
        SkinnedAnimationRuntime runtime = active().skinnedAnimationRuntime();
        if (runtime == null) {
            throw new IllegalStateException("BlendLib P5 skinned animation runtime is not installed by the client entrypoint");
        }
        return runtime;
    }

    /**
     * Explicit opt-in client render measurements for a benchmark or diagnostic harness.
     *
     * <p>The returned facade exposes immutable duration/count observations only. It does not
     * expose a renderer implementation, Minecraft global, core array, or raw GL object.</p>
     */
    public static ClientRenderMeasurementService performanceMeasurements() {
        return active().performanceMeasurements();
    }

    private static synchronized void install(
            ClientModelRegistry registry, ModelRenderBackend backend, SkinnedAnimationRuntime skinnedAnimationRuntime) {
        ClientModelRegistry checkedRegistry = Objects.requireNonNull(registry, "registry");
        ModelRenderBackend checkedBackend = Objects.requireNonNull(backend, "backend");
        Services current = ACTIVE.get();
        if (current == null) {
            ACTIVE.set(Services.create(checkedRegistry, checkedBackend, skinnedAnimationRuntime));
            return;
        }
        if (current.registry() != checkedRegistry || current.backend() != checkedBackend) {
            throw new IllegalStateException("BlendLib client services are already initialized for another adapter instance");
        }
        if (current.skinnedAnimationRuntime() == skinnedAnimationRuntime) {
            return;
        }
        if (skinnedAnimationRuntime == null || current.skinnedAnimationRuntime() != null) {
            throw new IllegalStateException("BlendLib client services cannot replace the installed P5 skinned animation runtime");
        }
        ACTIVE.set(current.withSkinnedAnimationRuntime(skinnedAnimationRuntime));
    }

    private static Services active() {
        Services services = ACTIVE.get();
        if (services == null) {
            throw new IllegalStateException("BlendLib client services are not initialized by the client entrypoint");
        }
        return services;
    }

    private record Services(
            ClientModelRegistry registry,
            ModelRenderBackend backend,
            ClientModelLookup models,
            BlendRenderer renderer,
            ClientDiagnosticsService diagnostics,
            ClientDiagnosticsCommandRegistry commands,
            SkinnedAnimationRuntime skinnedAnimationRuntime,
            ClientRenderMeasurementService performanceMeasurements) {
        private static Services create(
                ClientModelRegistry registry, ModelRenderBackend backend, SkinnedAnimationRuntime skinnedAnimationRuntime) {
            ClientModelRegistry checkedRegistry = Objects.requireNonNull(registry, "registry");
            ModelRenderBackend checkedBackend = Objects.requireNonNull(backend, "backend");
            ClientModelLookup models = checkedRegistry.installServiceLookup(MANAGED_LOOKUP_BOOTSTRAP);
            ClientDiagnosticsService diagnostics = new ClientDiagnosticsService(models);
            ClientRenderMeasurementService measurements = new ClientRenderMeasurementService(
                    () -> skinnedAnimationRuntime == null
                            ? ClientAnimationRuntimeMetrics.unavailable()
                            : skinnedAnimationRuntime.measurementSnapshot());
            return new Services(
                    checkedRegistry,
                    checkedBackend,
                    models,
                    new BlendRenderer(checkedBackend),
                    diagnostics,
                    new ClientDiagnosticsCommandRegistry(diagnostics),
                    skinnedAnimationRuntime,
                    measurements);
        }

        private Services withSkinnedAnimationRuntime(SkinnedAnimationRuntime skinnedAnimationRuntime) {
            return new Services(
                    registry,
                    backend,
                    models,
                    renderer,
                    diagnostics,
                    commands,
                    skinnedAnimationRuntime,
                    new ClientRenderMeasurementService(skinnedAnimationRuntime::measurementSnapshot));
        }
    }
}
