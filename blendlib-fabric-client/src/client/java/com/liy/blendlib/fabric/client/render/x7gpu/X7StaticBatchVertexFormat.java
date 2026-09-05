package com.liy.blendlib.fabric.client.render.x7gpu;

import java.util.Objects;

/**
 * Frozen vertex-layout identity retained by a candidate batch key.
 *
 * <p>It is an equality key only, not permission to upload or draw an arbitrary format. The B1
 * typed uploader currently produces {@link #POSITION_NORMAL_UV_F32} only.</p>
 */
record X7StaticBatchVertexFormat(String layoutId, int strideBytes) {
    static final X7StaticBatchVertexFormat POSITION_NORMAL_UV_F32 =
            new X7StaticBatchVertexFormat("POSITION_NORMAL_UV_F32", 8 * Float.BYTES);

    X7StaticBatchVertexFormat {
        layoutId = Objects.requireNonNull(layoutId, "layoutId");
        if (layoutId.isBlank() || strideBytes <= 0) {
            throw new IllegalArgumentException("X7 static batch format requires a nonblank layout and positive stride");
        }
    }
}
