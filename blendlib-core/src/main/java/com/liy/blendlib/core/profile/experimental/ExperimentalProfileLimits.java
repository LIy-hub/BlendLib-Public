package com.liy.blendlib.core.profile.experimental;

/**
 * Bounded input limits for the X9 validation-only profile candidate.
 *
 * <p>The candidate exposes its X9-specific descriptor/feature limits plus
 * {@link ExperimentalGlbLimits}; every public axis is consumed before the
 * corresponding untrusted collection allocation or feature traversal.</p>
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
        ExperimentalGlbLimits glbLimits) {
    /** Fixed X9 descriptor/schema and GLB-envelope ceilings; callers may only tighten them. */
    private static final int HARD_MAX_DESCRIPTOR_BYTES = 64 * 1024;
    private static final int HARD_MAX_MATERIALS = 256;
    private static final int HARD_MAX_CAPABILITIES = 32;
    private static final int HARD_MAX_MORPH_TARGETS_PER_PRIMITIVE = 64;
    private static final int HARD_MAX_MORPH_TARGETS_PER_MESH = 256;
    private static final int HARD_MAX_UV_SETS = 2;
    private static final int HARD_MAX_ANIMATION_SAMPLERS = 1_024;
    private static final int HARD_MAX_ANIMATIONS = 256;
    private static final double HARD_MAX_CLIP_DURATION_SECONDS = 600.0;

    public static final ExperimentalProfileLimits DEFAULT = new ExperimentalProfileLimits(
            HARD_MAX_DESCRIPTOR_BYTES,
            HARD_MAX_MATERIALS,
            HARD_MAX_CAPABILITIES,
            HARD_MAX_MORPH_TARGETS_PER_PRIMITIVE,
            HARD_MAX_MORPH_TARGETS_PER_MESH,
            HARD_MAX_UV_SETS,
            HARD_MAX_ANIMATION_SAMPLERS,
            HARD_MAX_ANIMATIONS,
            HARD_MAX_CLIP_DURATION_SECONDS,
            ExperimentalGlbLimits.DEFAULT);

    public ExperimentalProfileLimits {
        if (maxDescriptorBytes <= 0 || maxMaterials <= 0 || maxCapabilities <= 0 || maxMorphTargetsPerPrimitive <= 0
                || maxMorphTargetsPerMesh <= 0 || maxUvSets <= 0 || maxAnimationSamplers <= 0 || maxAnimations <= 0
                || !Double.isFinite(maxClipDurationSeconds) || maxClipDurationSeconds <= 0.0) {
            throw new IllegalArgumentException("X9 profile limits must be finite and positive");
        }
        if (glbLimits == null) {
            throw new NullPointerException("glbLimits");
        }
        if (maxDescriptorBytes > HARD_MAX_DESCRIPTOR_BYTES
                || maxMaterials > HARD_MAX_MATERIALS
                || maxCapabilities > HARD_MAX_CAPABILITIES
                || maxMorphTargetsPerPrimitive > HARD_MAX_MORPH_TARGETS_PER_PRIMITIVE
                || maxMorphTargetsPerMesh > HARD_MAX_MORPH_TARGETS_PER_MESH
                || maxUvSets > HARD_MAX_UV_SETS
                || maxAnimationSamplers > HARD_MAX_ANIMATION_SAMPLERS
                || maxAnimations > HARD_MAX_ANIMATIONS
                || maxClipDurationSeconds > HARD_MAX_CLIP_DURATION_SECONDS) {
            throw new IllegalArgumentException("X9 profile limits may tighten but must not expand fixed hard ceilings");
        }
    }

    public static ExperimentalProfileLimits defaults() {
        return DEFAULT;
    }
}
