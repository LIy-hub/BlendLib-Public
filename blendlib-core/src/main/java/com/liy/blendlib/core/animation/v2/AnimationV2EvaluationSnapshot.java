package com.liy.blendlib.core.animation.v2;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable output boundary for v2 evaluation. It intentionally carries no renderer, world, or resource handle. */
public final class AnimationV2EvaluationSnapshot {
    private final long revision;
    private final AnimationV2Pose pose;
    private final Map<BlendResourceId, AnimationV2ControllerPlayhead> playheads;
    private final List<AnimationV2Diagnostic> diagnostics;
    private final AnimationV2ObserverTraversal observerTraversal;

    public AnimationV2EvaluationSnapshot(
            long revision,
            AnimationV2Pose pose,
            Map<BlendResourceId, AnimationV2ControllerPlayhead> playheads,
            List<AnimationV2Diagnostic> diagnostics) {
        this(revision, pose, playheads, diagnostics, AnimationV2ObserverTraversal.empty());
    }

    /**
     * Package-private construction path used only by the owner runtime after it has frozen a bounded observer trace.
     * The established public four-argument constructor deliberately remains the compatibility boundary.
     */
    AnimationV2EvaluationSnapshot(
            long revision,
            AnimationV2Pose pose,
            Map<BlendResourceId, AnimationV2ControllerPlayhead> playheads,
            List<AnimationV2Diagnostic> diagnostics,
            AnimationV2ObserverTraversal observerTraversal) {
        if (revision < 0L) {
            throw new IllegalArgumentException("revision must be non-negative");
        }
        this.revision = revision;
        this.pose = Objects.requireNonNull(pose, "pose");
        Objects.requireNonNull(playheads, "playheads");
        LinkedHashMap<BlendResourceId, AnimationV2ControllerPlayhead> copied = new LinkedHashMap<>();
        for (Map.Entry<BlendResourceId, AnimationV2ControllerPlayhead> entry : playheads.entrySet()) {
            BlendResourceId controllerId = Objects.requireNonNull(entry.getKey(), "controllerId");
            AnimationV2Limits.requireCanonicalIdLength(controllerId.value(), "snapshot controller id");
            copied.put(controllerId, Objects.requireNonNull(entry.getValue(), "playhead"));
        }
        this.playheads = Collections.unmodifiableMap(copied);
        this.diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        this.observerTraversal = Objects.requireNonNull(observerTraversal, "observerTraversal");
    }

    public long revision() {
        return revision;
    }

    public AnimationV2Pose pose() {
        return pose;
    }

    public Map<BlendResourceId, AnimationV2ControllerPlayhead> playheads() {
        return playheads;
    }

    public List<AnimationV2Diagnostic> diagnostics() {
        return diagnostics;
    }

    /**
     * Immutable, bounded, observer-only automatic traversal metadata for this exact X2 publication.
     *
     * <p>A caller-created compatibility snapshot has an empty trace. Consumers that require provenance must still bind
     * object identity to an exact {@link AnimationV2InstanceRuntime#latestSnapshot()} publication.</p>
     */
    public AnimationV2ObserverTraversal observerTraversal() {
        return observerTraversal;
    }
}
