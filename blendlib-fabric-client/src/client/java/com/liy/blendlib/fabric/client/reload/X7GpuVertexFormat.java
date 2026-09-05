package com.liy.blendlib.fabric.client.reload;

/** Frozen B1 vertex layout: position.xyz, normal.xyz, uv.xy as eight little-endian floats. */
enum X7GpuVertexFormat {
    POSITION_NORMAL_UV_F32(8 * Float.BYTES);

    private final int strideBytes;

    X7GpuVertexFormat(int strideBytes) {
        this.strideBytes = strideBytes;
    }

    int strideBytes() {
        return strideBytes;
    }
}
