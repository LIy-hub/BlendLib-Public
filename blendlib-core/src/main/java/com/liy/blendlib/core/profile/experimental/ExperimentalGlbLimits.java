package com.liy.blendlib.core.profile.experimental;

import com.liy.blendlib.core.limits.BlendAssetLimits;

/**
 * Immutable GLB input ceilings actually consumed by the X9 validation-only candidate.
 *
 * <p>This type deliberately exposes only X9 structural and semantic input axes. The
 * package-private adapter exists solely to compose the frozen v1 GLB container/accessor
 * readers; it does not make v1-only concepts configurable through the X9 surface.</p>
 */
public record ExperimentalGlbLimits(
        int maxGlbBytes,
        int maxVertices,
        int maxIndices,
        int maxNodes,
        int maxSkinJoints,
        int maxHierarchyDepth,
        int maxKeyframeSamples) {
    private static final BlendAssetLimits FROZEN_V1_DEFAULTS = BlendAssetLimits.DEFAULT;

    /** Fixed X9 GLB ceilings; callers may only tighten them. */
    public static final ExperimentalGlbLimits DEFAULT = new ExperimentalGlbLimits(
            FROZEN_V1_DEFAULTS.maxGlbBytes(),
            FROZEN_V1_DEFAULTS.maxVertices(),
            FROZEN_V1_DEFAULTS.maxIndices(),
            FROZEN_V1_DEFAULTS.maxNodes(),
            FROZEN_V1_DEFAULTS.maxSkinJoints(),
            FROZEN_V1_DEFAULTS.maxHierarchyDepth(),
            FROZEN_V1_DEFAULTS.maxKeyframeSamples());

    public ExperimentalGlbLimits {
        if (maxGlbBytes <= 0 || maxVertices <= 0 || maxIndices <= 0 || maxNodes <= 0
                || maxSkinJoints <= 0 || maxHierarchyDepth <= 0 || maxKeyframeSamples <= 0) {
            throw new IllegalArgumentException("X9 GLB limits must be positive");
        }
        if (maxGlbBytes > FROZEN_V1_DEFAULTS.maxGlbBytes()
                || maxVertices > FROZEN_V1_DEFAULTS.maxVertices()
                || maxIndices > FROZEN_V1_DEFAULTS.maxIndices()
                || maxNodes > FROZEN_V1_DEFAULTS.maxNodes()
                || maxSkinJoints > FROZEN_V1_DEFAULTS.maxSkinJoints()
                || maxHierarchyDepth > FROZEN_V1_DEFAULTS.maxHierarchyDepth()
                || maxKeyframeSamples > FROZEN_V1_DEFAULTS.maxKeyframeSamples()) {
            throw new IllegalArgumentException("X9 GLB limits may tighten but must not expand fixed hard ceilings");
        }
    }

    /**
     * Supplies the existing strict reader with the frozen fields which have no X9 input
     * semantics. This adapter is intentionally package-private.
     */
    BlendAssetLimits asInternalBlendAssetLimits() {
        return new BlendAssetLimits(
                maxGlbBytes,
                maxVertices,
                maxIndices,
                maxNodes,
                FROZEN_V1_DEFAULTS.maxRigidNodes(),
                maxSkinJoints,
                maxHierarchyDepth,
                FROZEN_V1_DEFAULTS.maxClips(),
                maxKeyframeSamples,
                FROZEN_V1_DEFAULTS.maxClipDurationSeconds(),
                FROZEN_V1_DEFAULTS.maxMaterialSlots(),
                FROZEN_V1_DEFAULTS.maxSockets());
    }

    public static ExperimentalGlbLimits defaults() {
        return DEFAULT;
    }
}
