package com.liy.blendlib.core.procedural;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.api.ModelInstance;
import com.liy.blendlib.api.SocketQuery;
import com.liy.blendlib.core.animation.v2.AnimationV2Pose;
import com.liy.blendlib.core.model.Transform;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Fully frozen X3 output. Render/presentation code may read it concurrently but can never mutate it. */
public final class ProceduralFrameSnapshot {
    private final ModelInstance modelInstance;
    private final Object planIdentity;
    private final long sourceRevision;
    private final AnimationV2Pose localPose;
    private final List<Transform> modelTransforms;
    private final List<Boolean> boneVisibility;
    private final Map<ProceduralSurfaceTarget, Boolean> surfaceVisibility;
    private final Map<ProceduralSurfaceTarget, SemanticSurfaceOverride> surfaceOverrides;
    private final Map<BlendResourceId, ProceduralSocketTransform> socketTransforms;
    private final Set<BlendResourceId> hiddenSocketIds;
    private final List<ProceduralResolvedAttachment> attachments;
    private final ProceduralVisualEventBatch visualEvents;
    private final List<ProceduralDiagnostic> diagnostics;

    ProceduralFrameSnapshot(
            ModelInstance modelInstance,
            Object planIdentity,
            long sourceRevision,
            AnimationV2Pose localPose,
            List<Transform> modelTransforms,
            List<Boolean> boneVisibility,
            Map<ProceduralSurfaceTarget, Boolean> surfaceVisibility,
            Map<ProceduralSurfaceTarget, SemanticSurfaceOverride> surfaceOverrides,
            Map<BlendResourceId, ProceduralSocketTransform> socketTransforms,
            Set<BlendResourceId> hiddenSocketIds,
            List<ProceduralResolvedAttachment> attachments,
            ProceduralVisualEventBatch visualEvents,
            List<ProceduralDiagnostic> diagnostics) {
        this.modelInstance = Objects.requireNonNull(modelInstance, "modelInstance");
        this.planIdentity = Objects.requireNonNull(planIdentity, "planIdentity");
        if (sourceRevision < 0L) {
            throw new IllegalArgumentException("source revision must be non-negative");
        }
        this.sourceRevision = sourceRevision;
        this.localPose = Objects.requireNonNull(localPose, "localPose");
        this.modelTransforms = List.copyOf(Objects.requireNonNull(modelTransforms, "modelTransforms"));
        this.boneVisibility = List.copyOf(Objects.requireNonNull(boneVisibility, "boneVisibility"));
        this.surfaceVisibility = immutableMap(surfaceVisibility);
        this.surfaceOverrides = immutableMap(surfaceOverrides);
        this.socketTransforms = immutableMap(socketTransforms);
        this.hiddenSocketIds = Collections.unmodifiableSet(new LinkedHashSet<>(Objects.requireNonNull(hiddenSocketIds, "hiddenSocketIds")));
        this.attachments = List.copyOf(Objects.requireNonNull(attachments, "attachments"));
        this.visualEvents = Objects.requireNonNull(visualEvents, "visualEvents");
        this.diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    }

    public ModelInstance modelInstance() {
        return modelInstance;
    }

    /**
     * Tests an opaque runtime-issued binding without exposing a forgeable hierarchy token. This X3 internal boundary
     * lets client-only IK reject a same-model/generation snapshot that originated from another frozen rig plan.
     */
    public boolean matchesPlan(ProceduralRigPlan plan) {
        return Objects.requireNonNull(plan, "plan").matchesSnapshotPlanIdentity(planIdentity);
    }

    public long sourceRevision() {
        return sourceRevision;
    }

    public AnimationV2Pose localPose() {
        return localPose;
    }

    public List<Transform> modelTransforms() {
        return modelTransforms;
    }

    public List<Boolean> boneVisibility() {
        return boneVisibility;
    }

    public Map<ProceduralSurfaceTarget, Boolean> surfaceVisibility() {
        return surfaceVisibility;
    }

    public Map<ProceduralSurfaceTarget, SemanticSurfaceOverride> surfaceOverrides() {
        return surfaceOverrides;
    }

    public Optional<ProceduralSocketTransform> socketTransform(BlendResourceId socketId) {
        return Optional.ofNullable(socketTransforms.get(Objects.requireNonNull(socketId, "socketId")));
    }

    public List<ProceduralResolvedAttachment> attachments() {
        return attachments;
    }

    public ProceduralVisualEventBatch visualEvents() {
        return visualEvents;
    }

    public List<ProceduralDiagnostic> diagnostics() {
        return diagnostics;
    }

    /** Reuses the existing stable semantic SocketQuery rather than inventing an X3 alias. */
    public ProceduralSocketResolution resolveSocket(SocketQuery query) {
        Objects.requireNonNull(query, "query");
        if (!modelInstance.equals(query.modelInstance())) {
            return ProceduralSocketResolution.rejected(new ProceduralDiagnostic(
                    ProceduralDiagnosticSeverity.ERROR,
                    ProceduralDiagnosticCode.SOCKET_SCOPE_MISMATCH,
                    query.socketId(),
                    "socket query model-instance or generation does not match this frozen snapshot"));
        }
        ProceduralSocketTransform transform = socketTransforms.get(query.socketId());
        if (transform != null) {
            return ProceduralSocketResolution.found(transform.transform());
        }
        ProceduralDiagnosticCode code = hiddenSocketIds.contains(query.socketId())
                ? ProceduralDiagnosticCode.HIDDEN_SOCKET : ProceduralDiagnosticCode.SOCKET_MISSING;
        return ProceduralSocketResolution.rejected(new ProceduralDiagnostic(
                ProceduralDiagnosticSeverity.WARN,
                code,
                query.socketId(),
                code == ProceduralDiagnosticCode.HIDDEN_SOCKET
                        ? "socket target is hidden in this frozen frame"
                        : "socket id is absent from this frozen rig frame"));
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> source) {
        Objects.requireNonNull(source, "source");
        LinkedHashMap<K, V> copied = new LinkedHashMap<>();
        for (Map.Entry<K, V> entry : source.entrySet()) {
            copied.put(Objects.requireNonNull(entry.getKey(), "map key"), Objects.requireNonNull(entry.getValue(), "map value"));
        }
        return Collections.unmodifiableMap(copied);
    }
}
