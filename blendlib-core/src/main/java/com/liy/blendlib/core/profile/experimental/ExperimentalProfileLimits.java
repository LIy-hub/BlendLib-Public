package com.liy.blendlib.core.profile.experimental;

import com.liy.blendlib.core.limits.BlendAssetLimits;

/**
 * Bounded input limits for the X9 validation-only profile candidate.
 *
 * <p>The candidate deliberately reuses the frozen v1 GLB byte/geometry
 * limits. Its extra limits are checked before collection allocation or
 * feature traversal.</p>
 */
public record ExperimentalProfileLimits(
        int maxDescriptorBytes,
        int maxMaterials,
        int maxCapabilities,
        int maxMorphTargetsPerPrimitive,
        int maxMorphTargetsPerMesh,
        int maxUvSets,
        int maxAnimationSamplers,
        int maxAnimations,
        double maxClipDurationSeconds,
        BlendAssetLimits baseGlbLimits) {
    public static final ExperimentalProfileLimits DEFAULT = new ExperimentalProfileLimits(
            64 * 1024,
            256,
            32,
            64,
            256,
            2,
            1_024,
            256,
            600.0,
            BlendAssetLimits.DEFAULT);

    public ExperimentalProfileLimits {
        if (maxDescriptorBytes <= 0 || maxMaterials <= 0 || maxCapabilities <= 0 || maxMorphTargetsPerPrimitive <= 0
                || maxMorphTargetsPerMesh <= 0 || maxUvSets < 2 || maxAnimationSamplers <= 0 || maxAnimations <= 0
                || !Double.isFinite(maxClipDurationSeconds) || maxClipDurationSeconds <= 0.0) {
            throw new IllegalArgumentException("X9 profile limits must be finite and positive");
        }
        if (baseGlbLimits == null) {
            throw new NullPointerException("baseGlbLimits");
        }
    }

    public static ExperimentalProfileLimits defaults() {
        return DEFAULT;
    }
}
