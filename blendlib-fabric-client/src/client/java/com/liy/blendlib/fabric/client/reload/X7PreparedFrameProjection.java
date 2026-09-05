package com.liy.blendlib.fabric.client.reload;

import com.liy.blendlib.fabric.client.api.ClientModelView;
import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import com.liy.blendlib.fabric.client.render.RenderVisibility;
import java.util.Objects;

/**
 * Immutable source-bound CPU/LOD0 projection for one already-prepared snapshot.
 *
 * <p>This candidate has no verified client frame callback. Consequently every instance carries
 * the explicit unavailable status below: it may validate the generation-frozen CPU route, but it
 * cannot claim a frame ledger, action execution, draw, or completed-frame metric.</p>
 */
final class X7PreparedFrameProjection {
    enum FrameProducerStatus {
        UNAVAILABLE_NO_VERIFIED_FRAME_BOUNDARY
    }

    private final ClientModelView sourceView;
    private final ModelRenderSnapshot snapshot;
    private final ModelHandle lodZeroHandle;
    private final X7GenerationPerformancePlan.CpuSubsetPublished cpuRoute;
    private final X7CullingPolicy.InstanceDecision instanceDecision;

    private X7PreparedFrameProjection(
            ClientModelView sourceView,
            ModelRenderSnapshot snapshot,
            ModelHandle lodZeroHandle,
            X7GenerationPerformancePlan.CpuSubsetPublished cpuRoute,
            X7CullingPolicy.InstanceDecision instanceDecision) {
        this.sourceView = sourceView;
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.lodZeroHandle = lodZeroHandle;
        this.cpuRoute = cpuRoute;
        this.instanceDecision = instanceDecision;
    }

    static X7PreparedFrameProjection unavailable(
            ClientModelView sourceView,
            ModelRenderSnapshot snapshot,
            ModelHandle lodZeroHandle,
            X7GenerationPerformancePlan.CpuSubsetPublished cpuRoute) {
        ClientModelView checkedSourceView = Objects.requireNonNull(sourceView, "sourceView");
        ModelRenderSnapshot checkedSnapshot = Objects.requireNonNull(snapshot, "snapshot");
        ModelHandle checkedLodZeroHandle = Objects.requireNonNull(lodZeroHandle, "lodZeroHandle");
        X7GenerationPerformancePlan.CpuSubsetPublished checkedCpuRoute = Objects.requireNonNull(cpuRoute, "cpuRoute");
        if (checkedSourceView.renderHandle() != checkedSnapshot.handle()
                || checkedLodZeroHandle.renderHandle() != checkedSnapshot.handle()
                || checkedLodZeroHandle.generationId() != checkedSnapshot.generation()) {
            throw new IllegalArgumentException("X7 prepared projection requires the exact source, LOD0, and snapshot handle");
        }
        checkedCpuRoute.requireCurrentCpuBinding(
                checkedSnapshot.generation(), checkedSnapshot.handle(), checkedLodZeroHandle);
        X7CullingPolicy.InstanceDecision decision = X7CullingPolicy.decideInstance(
                new X7CullingPolicy.InstanceTarget(checkedSnapshot.generation(), checkedSnapshot.handle()),
                new X7CullingPolicy.Evidence(
                        checkedSnapshot.visibility() == RenderVisibility.CULLED
                                ? X7CullingPolicy.Visibility.HIDDEN
                                : X7CullingPolicy.Visibility.VISIBLE,
                        true));
        return new X7PreparedFrameProjection(
                checkedSourceView, checkedSnapshot, checkedLodZeroHandle, checkedCpuRoute, decision);
    }

    /** Conservative compatibility fallback for external lookups and old snapshots. */
    static X7PreparedFrameProjection externalFallback(ModelRenderSnapshot snapshot) {
        return new X7PreparedFrameProjection(
                null, Objects.requireNonNull(snapshot, "snapshot"), null, null, null);
    }

    FrameProducerStatus frameProducerStatus() {
        return FrameProducerStatus.UNAVAILABLE_NO_VERIFIED_FRAME_BOUNDARY;
    }

    boolean frameProducerAvailable() {
        return false;
    }

    int selectedLodLevel() {
        return 0;
    }

    /** Validates only the frozen CPU route; it does not execute or count a backend action. */
    boolean permitsCpuRoute(ModelRenderSnapshot expectedSnapshot, ClientModelView expectedSourceView) {
        ModelRenderSnapshot checkedSnapshot = Objects.requireNonNull(expectedSnapshot, "expectedSnapshot");
        if (snapshot != checkedSnapshot) {
            throw new IllegalArgumentException("X7 prepared projection belongs to another immutable render snapshot");
        }
        if (cpuRoute == null) {
            return true;
        }
        if (sourceView != expectedSourceView) {
            throw new IllegalStateException("X7 prepared projection lost its exact source-view proof");
        }
        cpuRoute.requireCurrentCpuBinding(
                checkedSnapshot.generation(), checkedSnapshot.handle(), lodZeroHandle);
        X7CullingPolicy.CurrentInstanceDecision current = instanceDecision.readCurrent(
                checkedSnapshot.generation(), checkedSnapshot.handle());
        boolean culledByProjection = current.decision() == X7CullingPolicy.Decision.CULL;
        boolean culledBySnapshot = checkedSnapshot.visibility() == RenderVisibility.CULLED;
        if (culledByProjection != culledBySnapshot) {
            throw new IllegalStateException("X7 prepared projection must preserve the snapshot visibility decision");
        }
        if (cpuRoute.backend() != X7GenerationPerformancePlan.BackendChoice.CPU
                || cpuRoute.fallbackReason() != X7GenerationPerformancePlan.FallbackReason.CAPABILITY_UNAVAILABLE) {
            throw new IllegalStateException("X7 CPU/LOD0 projection cannot claim an unavailable GPU route");
        }
        return true;
    }
}
