package com.liy.blendlib.core.limits;

/** Immutable v1 hard limits used before allocating or walking untrusted data. */
public record BlendAssetLimits(
        int maxGlbBytes,
        int maxVertices,
        int maxIndices,
        int maxNodes,
        int maxRigidNodes,
        int maxSkinJoints,
        int maxHierarchyDepth,
        int maxClips,
        int maxKeyframeSamples,
        double maxClipDurationSeconds,
        int maxMaterialSlots,
        int maxSockets) {
    /** Descriptor/runtime animation speed ceiling, aligned with the v1 synchronized payload ceiling. */
    public static final double MAX_ANIMATION_SPEED = 64.0;
    /** Maximum number of declared controller states in one descriptor/runtime definition. */
    public static final int MAX_ANIMATION_STATES = 256;
    /** Maximum number of visual-event declarations on one animation state. */
    public static final int MAX_VISUAL_EVENTS_PER_STATE = 4_096;
    /** Maximum number of visual-event declarations across one animation descriptor. */
    public static final int MAX_VISUAL_EVENTS_PER_DESCRIPTOR = 16_384;
    /** Maximum loop cycles crossed by one controller advance call. */
    public static final int MAX_LOOP_CYCLES_PER_ADVANCE = 4_096;
    /** Maximum automatic next-state transitions performed by one controller advance call. */
    public static final int MAX_STATE_TRANSITIONS_PER_ADVANCE = 4_096;
    /** Maximum visual events returned by one controller advance call. */
    public static final int MAX_VISUAL_EVENTS_PER_ADVANCE = 16_384;
    /** Maximum number of accessor declarations accepted from one strict GLB JSON document. */
    public static final int MAX_DECLARED_ACCESSORS = 16_384;

    /** Frozen default limits from docs/error-codes-v1.md. */
    public static final BlendAssetLimits DEFAULT = new BlendAssetLimits(
            64 * 1024 * 1024,
            1_000_000,
            3_000_000,
            4_096,
            4_096,
            512,
            256,
            256,
            1_000_000,
            600.0,
            256,
            512);

    public BlendAssetLimits {
        if (maxGlbBytes <= 0 || maxVertices <= 0 || maxIndices <= 0 || maxNodes <= 0
                || maxRigidNodes <= 0 || maxSkinJoints <= 0 || maxHierarchyDepth <= 0
                || maxClips <= 0 || maxKeyframeSamples <= 0 || maxMaterialSlots <= 0 || maxSockets <= 0
                || !Double.isFinite(maxClipDurationSeconds) || maxClipDurationSeconds <= 0.0) {
            throw new IllegalArgumentException("All asset limits must be finite and positive");
        }
    }

    public static BlendAssetLimits defaults() {
        return DEFAULT;
    }
}
