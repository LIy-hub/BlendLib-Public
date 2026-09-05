package com.liy.blendlib.fabric.v262.model;

/**
 * Immutable render-time values copied into one Fabric 26.2 model snapshot.
 *
 * <p><strong>Platform target:</strong> Fabric on Minecraft 26.2. This value contains no world,
 * entity, resource manager, or mutable renderer state. It is deliberately narrow so submission
 * does not discover providers, parse GLB/JSON, or perform I/O.</p>
 *
 * @param packedLight Minecraft packed light value
 * @param packedOverlay Minecraft packed overlay value
 * @param argbTint immutable ARGB vertex tint
 */
public record Fabric262FrameState(int packedLight, int packedOverlay, int argbTint) {
    /**
     * Creates the standard opaque white frame state for caller-selected light and overlay values.
     *
     * @param packedLight Minecraft packed light value
     * @param packedOverlay Minecraft packed overlay value
     * @return immutable white-tint frame state
     */
    public static Fabric262FrameState white(int packedLight, int packedOverlay) {
        return new Fabric262FrameState(packedLight, packedOverlay, 0xFFFFFFFF);
    }
}
