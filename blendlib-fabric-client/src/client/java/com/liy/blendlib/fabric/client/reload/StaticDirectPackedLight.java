package com.liy.blendlib.fabric.client.reload;

/** Exact 26.1.2 lightmap-coordinate admission for the direct-static sampler path. */
final class StaticDirectPackedLight {
    static final int MAX_LIGHTMAP_COORDINATE = 15 << 4;

    private StaticDirectPackedLight() {
    }

    /**
     * Keeps CPU for values that cannot be represented as the two vanilla 16x16 lightmap texel coordinates.
     *
     * <p>Valid packed lanes are the same zero-to-240, 16-step values written by Minecraft's light UV2 vertex
     * contract. The direct shader carries them losslessly and calls the vanilla sample_lightmap include; it never
     * derives a scalar brightness.</p>
     */
    static int requireExactlyRepresentable(int packedLight) {
        int block = packedLight & 0xFFFF;
        int sky = packedLight >>> 16 & 0xFFFF;
        if (block > MAX_LIGHTMAP_COORDINATE
                || sky > MAX_LIGHTMAP_COORDINATE
                || (block & 0xF) != 0
                || (sky & 0xF) != 0) {
            throw new IllegalArgumentException("The direct-static candidate requires exact Minecraft packed-light lanes");
        }
        return packedLight;
    }
}
