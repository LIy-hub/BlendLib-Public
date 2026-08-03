package com.liy.blendlib.fabric.client.entity;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable rotation-only view of one cached entity animation pose.
 *
 * <p>A modifier reads sampled rotations with {@link #rotation(int)} and returns either this view
 * unchanged or a derived view produced by {@link #withRotation(int, BlendEntityRotation)} or
 * {@link #withRotations(Map)}. Node membership cannot be changed, and translation and scale are
 * deliberately absent from this public client-adapter contract.</p>
 */
public final class BlendEntityRotationPose {
    private final Map<Integer, BlendEntityRotation> baseRotations;
    private final Map<Integer, BlendEntityRotation> rotationOverrides;
    private final Object sourceIdentity;

    private BlendEntityRotationPose(
            Map<Integer, BlendEntityRotation> baseRotations,
            Map<Integer, BlendEntityRotation> rotationOverrides,
            Object sourceIdentity) {
        this.baseRotations = baseRotations;
        this.rotationOverrides = Collections.unmodifiableMap(new LinkedHashMap<>(rotationOverrides));
        this.sourceIdentity = sourceIdentity;
    }

    static BlendEntityRotationPose capture(Map<Integer, BlendEntityRotation> baseRotations) {
        Objects.requireNonNull(baseRotations, "baseRotations");
        LinkedHashMap<Integer, BlendEntityRotation> frozenBase = new LinkedHashMap<>();
        for (Map.Entry<Integer, BlendEntityRotation> entry : baseRotations.entrySet()) {
            Integer nodeIndex = Objects.requireNonNull(entry.getKey(), "nodeIndex");
            if (nodeIndex < 0) {
                throw new IllegalArgumentException("Entity pose node indices must be non-negative");
            }
            frozenBase.put(nodeIndex, Objects.requireNonNull(entry.getValue(), "baseRotation"));
        }
        return new BlendEntityRotationPose(
                Collections.unmodifiableMap(frozenBase), Map.of(), new Object());
    }

    /** Returns the immutable, complete node-index domain of the sampled base pose. */
    public Set<Integer> nodeIndices() {
        return baseRotations.keySet();
    }

    /** Returns the current effective rotation for one node, including any derived override. */
    public BlendEntityRotation rotation(int nodeIndex) {
        requireKnownNode(nodeIndex);
        return rotationOverrides.getOrDefault(nodeIndex, baseRotations.get(nodeIndex));
    }

    /** Returns the immutable sparse override map accumulated by this derived view. */
    public Map<Integer, BlendEntityRotation> rotationOverrides() {
        return rotationOverrides;
    }

    /** Returns a new view with one finite normalized node rotation override. */
    public BlendEntityRotationPose withRotation(int nodeIndex, BlendEntityRotation rotation) {
        return withRotations(Map.of(nodeIndex, Objects.requireNonNull(rotation, "rotation")));
    }

    /** Returns a new view with the supplied finite normalized node rotation overrides. */
    public BlendEntityRotationPose withRotations(Map<Integer, BlendEntityRotation> rotations) {
        Objects.requireNonNull(rotations, "rotations");
        LinkedHashMap<Integer, BlendEntityRotation> updated = new LinkedHashMap<>(rotationOverrides);
        for (Map.Entry<Integer, BlendEntityRotation> entry : rotations.entrySet()) {
            Integer nodeIndex = Objects.requireNonNull(entry.getKey(), "nodeIndex");
            requireKnownNode(nodeIndex);
            BlendEntityRotation rotation = Objects.requireNonNull(entry.getValue(), "rotation");
            if (rotation.equals(baseRotations.get(nodeIndex))) {
                updated.remove(nodeIndex);
            } else {
                updated.put(nodeIndex, rotation);
            }
        }
        return new BlendEntityRotationPose(baseRotations, updated, sourceIdentity);
    }

    boolean isDerivedFrom(BlendEntityRotationPose basePose) {
        BlendEntityRotationPose checkedBase = Objects.requireNonNull(basePose, "basePose");
        return sourceIdentity == checkedBase.sourceIdentity && baseRotations == checkedBase.baseRotations;
    }

    private void requireKnownNode(int nodeIndex) {
        if (!baseRotations.containsKey(nodeIndex)) {
            throw new IllegalArgumentException("Entity rotation pose does not contain node index: " + nodeIndex);
        }
    }
}
