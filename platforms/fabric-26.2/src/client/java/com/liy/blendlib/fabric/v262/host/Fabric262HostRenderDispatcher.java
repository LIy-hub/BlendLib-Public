package com.liy.blendlib.fabric.v262.host;

import com.liy.blendlib.api.AnimationRequest;
import com.liy.blendlib.api.HostRegistrationSpec;
import com.liy.blendlib.fabric.v262.diagnostic.Fabric262Diagnostic;
import com.liy.blendlib.fabric.v262.diagnostic.Fabric262DiagnosticCode;
import com.liy.blendlib.fabric.v262.diagnostic.Fabric262PlatformException;
import com.liy.blendlib.fabric.v262.model.Fabric262FrameState;
import com.liy.blendlib.fabric.v262.model.Fabric262PoseSnapshot;
import com.liy.blendlib.fabric.v262.model.Fabric262PreparedModelHandle;
import com.liy.blendlib.fabric.v262.model.Fabric262RenderSnapshot;
import com.liy.blendlib.fabric.v262.model.Fabric262RenderSubmitter;
import com.liy.blendlib.fabric.v262.resource.Fabric262ResourceReloadCoordinator;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * Real public-API Fabric 26.2 host dispatcher.
 *
 * <p>One accepted stable registration installs an entity, block-entity, or item native hook and
 * records its semantic binding atomically. Native render callbacks acquire only a prepared,
 * generation-pinned snapshot and submit it through {@link Fabric262RenderSubmitter}; resource
 * discovery and decoding remain in the reload coordinator. Close drains all leases owned by this
 * dispatcher and removes its item model bindings before the adapter reports terminal cleanup.</p>
 */
public final class Fabric262HostRenderDispatcher implements AutoCloseable {
    private static final int MAX_LIVE_SNAPSHOTS = 4_096;
    private static final int MAX_ANIMATION_DIAGNOSTICS = 64;
    private final Fabric262ResourceReloadCoordinator coordinator;
    private final Fabric262HostRegistrationTranslator translator;
    private final Fabric262HostBindingRegistry bindings;
    private final Fabric262HostAnimationRegistry animations;
    private final Fabric262RenderSubmitter submitter;
    private final Object snapshotMonitor = new Object();
    private final Object lifecycleMonitor = new Object();
    private final Object diagnosticMonitor = new Object();
    private final Set<Fabric262RenderSnapshot> liveSnapshots =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final ArrayDeque<Fabric262Diagnostic> animationDiagnostics = new ArrayDeque<>();
    private volatile boolean closing;
    private boolean closed;
    private boolean itemBindingsReleased;
    private boolean animationStateReleased;
    private boolean bindingsReleased;

    /**
     * Creates the one client-runtime-owned dispatcher.
     *
     * @param coordinator active strict reload coordinator
     * @param translator native host token translator
     * @param bindings exact adapter-owned binding registry
     */
    public Fabric262HostRenderDispatcher(
            Fabric262ResourceReloadCoordinator coordinator,
            Fabric262HostRegistrationTranslator translator,
            Fabric262HostBindingRegistry bindings) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.translator = Objects.requireNonNull(translator, "translator");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.animations = new Fabric262HostAnimationRegistry();
        this.submitter = new Fabric262RenderSubmitter();
        Fabric262ItemModelBindings.installPluginOnce();
    }

    /**
     * Installs one native Fabric host dispatcher and commits the semantic binding exactly once.
     *
     * @param specification completed stable registration specification
     * @return immutable installed binding
     */
    public Fabric262HostBinding register(HostRegistrationSpec<?> specification) {
        synchronized (lifecycleMonitor) {
            if (closing) {
                throw closedFailure();
            }
            HostRegistrationSpec<?> checked = Objects.requireNonNull(specification, "specification");
            Fabric262RegisteredHost registered = translator.translateInstalled(checked);
            Fabric262HostBinding binding = registered.binding();
            bindings.register(binding, () -> installNative(checked, registered));
            return binding;
        }
    }

    /**
     * Returns true once close begins and every native callback must become inert.
     *
     * <p>The terminal cleanup flag is set only after all dispatcher-owned generation pins have
     * drained successfully. Returning {@code true} while that cleanup is retryable prevents a
     * registered Fabric callback from acquiring a new snapshot during the close transaction.</p>
     */
    public boolean isClosed() {
        return closing;
    }

    /**
     * Returns bounded immutable diagnostics for extraction-time animation source/sample fallback.
     *
     * <p>Diagnostics are adapter-local observability only; they do not change a host's strict
     * safe-rest-pose behavior or expose native host objects through API/core.</p>
     */
    public List<Fabric262Diagnostic> animationDiagnostics() {
        synchronized (diagnosticMonitor) {
            return List.copyOf(animationDiagnostics);
        }
    }

    Fabric262DispatchSnapshot acquireEntityOrNull(
            Fabric262RegisteredHost registered,
            Entity entity,
            Fabric262FrameState frame,
            double observedTicks) {
        Fabric262RegisteredHost checkedRegistered = Objects.requireNonNull(registered, "registered");
        Entity checkedEntity = Objects.requireNonNull(entity, "entity");
        Fabric262RenderSnapshot snapshot = acquireRawOrNull(checkedRegistered.binding().modelKey(), frame);
        if (snapshot == null) {
            return null;
        }
        AnimationExtraction extraction = animationFor(
                checkedRegistered,
                snapshot,
                request -> animations.extractEntity(checkedEntity, snapshot, request, observedTicks));
        return new Fabric262DispatchSnapshot(this, snapshot, extraction.pose(), extraction.diagnostic());
    }

    Fabric262DispatchSnapshot acquireBlockEntityOrNull(
            Fabric262RegisteredHost registered,
            BlockEntity blockEntity,
            Fabric262FrameState frame,
            double observedTicks) {
        Fabric262RegisteredHost checkedRegistered = Objects.requireNonNull(registered, "registered");
        BlockEntity checkedBlockEntity = Objects.requireNonNull(blockEntity, "blockEntity");
        Fabric262RenderSnapshot snapshot = acquireRawOrNull(checkedRegistered.binding().modelKey(), frame);
        if (snapshot == null) {
            return null;
        }
        AnimationExtraction extraction = animationFor(
                checkedRegistered,
                snapshot,
                request -> animations.extractBlockEntity(checkedBlockEntity, snapshot, request, observedTicks));
        return new Fabric262DispatchSnapshot(this, snapshot, extraction.pose(), extraction.diagnostic());
    }

    Fabric262DispatchSnapshot acquireItemOrNull(Fabric262RegisteredHost registered) {
        Fabric262RegisteredHost checkedRegistered = Objects.requireNonNull(registered, "registered");
        Fabric262RenderSnapshot snapshot = acquireRawOrNull(
                checkedRegistered.binding().modelKey(), Fabric262FrameState.white(0, 0));
        if (snapshot == null) {
            return null;
        }
        // Items intentionally have no persistent ItemStack identity. Sampling a bounded phase from
        // monotonic extraction time preserves LOOP motion without retaining the stack or a world.
        double observedSeconds = (double) (System.nanoTime() & Long.MAX_VALUE) / 1_000_000_000.0d;
        AnimationExtraction extraction = animationFor(
                checkedRegistered,
                snapshot,
                request -> animations.extractStatelessItem(snapshot, request, observedSeconds));
        return new Fabric262DispatchSnapshot(this, snapshot, extraction.pose(), extraction.diagnostic());
    }

    private Fabric262RenderSnapshot acquireRawOrNull(
            com.liy.blendlib.api.BlendModelKey modelKey,
            Fabric262FrameState frame) {
        com.liy.blendlib.api.BlendModelKey checkedKey = Objects.requireNonNull(modelKey, "modelKey");
        Fabric262FrameState checkedFrame = Objects.requireNonNull(frame, "frame");
        synchronized (snapshotMonitor) {
            if (closing) {
                return null;
            }
            if (liveSnapshots.size() >= MAX_LIVE_SNAPSHOTS) {
                // A dropped native extraction argument is still exactly owned and drainable by
                // this dispatcher. Refuse new pins before such dropped callbacks can grow the
                // retry inventory without bound.
                return null;
            }
            try {
                Fabric262RenderSnapshot snapshot = coordinator.snapshot(checkedKey, checkedFrame);
                liveSnapshots.add(snapshot);
                return snapshot;
            } catch (Fabric262PlatformException exception) {
                if (exception.diagnostic().code() == Fabric262DiagnosticCode.RUNTIME_CLOSED) {
                    return null;
                }
                throw exception;
            }
        }
    }

    void release(Fabric262RenderSnapshot snapshot) {
        Fabric262RenderSnapshot checkedSnapshot = Objects.requireNonNull(snapshot, "snapshot");
        synchronized (snapshotMonitor) {
            if (liveSnapshots.contains(checkedSnapshot)) {
                checkedSnapshot.close();
                // Only a successful exact generation release removes the lease from the owner's
                // retry inventory. A thrown release leaves it visible to dispatcher.close().
                liveSnapshots.remove(checkedSnapshot);
            }
        }
    }

    /**
     * Drains all issued snapshot leases and removes adapter-owned registration state exactly once.
     *
     * <p>Fabric's public renderer and model-loading registries deliberately have no general
     * removal API. This method makes those registered callbacks inert by closing this dispatcher,
     * drains every live generation pin, removes item bindings, and then clears local metadata.
     * A failed step remains in this dispatcher-owned inventory; a later exact runtime close retries
     * only the unfinished work rather than rethrowing an old cached exception.</p>
     */
    @Override
    public void close() {
        synchronized (lifecycleMonitor) {
            if (closed) {
                return;
            }
            closing = true;
            List<Fabric262RenderSnapshot> draining;
            synchronized (snapshotMonitor) {
                draining = new ArrayList<>(liveSnapshots);
            }
            RuntimeException failure = null;
            for (Fabric262RenderSnapshot snapshot : draining) {
                try {
                    release(snapshot);
                } catch (RuntimeException exception) {
                    failure = retainFailure(failure, exception);
                }
            }
            synchronized (snapshotMonitor) {
                if (!liveSnapshots.isEmpty() && failure == null) {
                    failure = new IllegalStateException(
                            "Fabric 26.2 dispatcher still owns an unreleased generation snapshot");
                }
            }
            if (failure == null && !itemBindingsReleased) {
                try {
                    Fabric262ItemModelBindings.unregisterOwner(this);
                    itemBindingsReleased = true;
                } catch (RuntimeException exception) {
                    failure = retainFailure(failure, exception);
                }
            }
            if (failure == null && !animationStateReleased) {
                try {
                    animations.close();
                    animationStateReleased = true;
                } catch (RuntimeException exception) {
                    failure = retainFailure(failure, exception);
                }
            }
            if (failure == null && !bindingsReleased) {
                try {
                    bindings.close();
                    bindingsReleased = true;
                } catch (RuntimeException exception) {
                    failure = retainFailure(failure, exception);
                }
            }
            if (failure != null) {
                throw failure;
            }
            closed = true;
        }
    }

    private void installNative(HostRegistrationSpec<?> specification, Fabric262RegisteredHost registered) {
        switch (specification.hostKind()) {
            case ENTITY -> installEntity(entityType(specification.host()), registered);
            case BLOCK_ENTITY -> installBlockEntity(blockEntityType(specification.host()), registered);
            case ITEM -> installItem((Item) specification.host(), registered);
        }
    }

    private <E extends Entity> void installEntity(EntityType<E> entityType, Fabric262RegisteredHost registered) {
        EntityRendererRegistry.register(
                Objects.requireNonNull(entityType, "entityType"),
                context -> new Fabric262EntityRenderer<>(context, registered, this, submitter));
    }

    private <T extends BlockEntity> void installBlockEntity(
            BlockEntityType<T> blockEntityType,
            Fabric262RegisteredHost registered) {
        BlockEntityRendererRegistry.register(
                Objects.requireNonNull(blockEntityType, "blockEntityType"),
                context -> new Fabric262BlockEntityRenderer<>(context, registered, this, submitter));
    }

    private void installItem(Item item, Fabric262RegisteredHost registered) {
        Fabric262ItemModelBindings.register(Objects.requireNonNull(item, "item"), registered, this);
    }

    @SuppressWarnings("unchecked")
    private static <E extends Entity> EntityType<E> entityType(Object host) {
        return (EntityType<E>) host;
    }

    @SuppressWarnings("unchecked")
    private static <T extends BlockEntity> BlockEntityType<T> blockEntityType(Object host) {
        return (BlockEntityType<T>) host;
    }

    private static Fabric262PlatformException closedFailure() {
        return new Fabric262PlatformException(Fabric262Diagnostic.error(
                Fabric262DiagnosticCode.RUNTIME_CLOSED,
                "Fabric 26.2 host dispatcher is closed and cannot install another native renderer"));
    }

    private static RuntimeException retainFailure(RuntimeException primary, RuntimeException next) {
        if (primary == null) {
            return next;
        }
        if (next != primary) {
            primary.addSuppressed(next);
        }
        return primary;
    }

    private AnimationExtraction animationFor(
            Fabric262RegisteredHost registered,
            Fabric262RenderSnapshot snapshot,
            AnimationExtractionStep step) {
        Fabric262PreparedModelHandle handle = snapshot.preparedHandle();
        try {
            // This evaluation deliberately occurs after the raw generation lease is pinned but
            // outside lifecycleMonitor/snapshotMonitor. The source sees only the original typed
            // registration token through Fabric262RegisteredHost, never a native live host cast.
            AnimationRequest request = registered.animationRequest();
            return new AnimationExtraction(step.freeze(request), null);
        } catch (RuntimeException exception) {
            Fabric262Diagnostic diagnostic = Fabric262Diagnostic.error(
                    Fabric262DiagnosticCode.ANIMATION_REQUEST_FAILURE,
                    snapshot.modelKey(),
                    "Fabric 26.2 animation source or pose sample failed; strict rest pose used: "
                            + boundedExceptionMessage(exception));
            recordAnimationDiagnostic(diagnostic);
            return new AnimationExtraction(handle.restPose(), diagnostic);
        }
    }

    private static String boundedExceptionMessage(RuntimeException exception) {
        String message = exception.getMessage();
        String detail = exception.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
        return detail.length() <= 360 ? detail : detail.substring(0, 357) + "...";
    }

    private void recordAnimationDiagnostic(Fabric262Diagnostic diagnostic) {
        synchronized (diagnosticMonitor) {
            while (animationDiagnostics.size() >= MAX_ANIMATION_DIAGNOSTICS) {
                animationDiagnostics.removeFirst();
            }
            animationDiagnostics.addLast(Objects.requireNonNull(diagnostic, "diagnostic"));
        }
    }

    @FunctionalInterface
    private interface AnimationExtractionStep {
        Fabric262PoseSnapshot freeze(AnimationRequest request);
    }

    private record AnimationExtraction(Fabric262PoseSnapshot pose, Fabric262Diagnostic diagnostic) {
        private AnimationExtraction {
            pose = Objects.requireNonNull(pose, "pose");
        }
    }
}
