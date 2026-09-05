package com.liy.blendlib.fabric.client.reload;

/** Frozen B1 index layout: unsigned 32-bit values encoded little-endian. */
enum X7GpuIndexType {
    UINT32_LE(Integer.BYTES);

    private final int bytesPerIndex;

    X7GpuIndexType(int bytesPerIndex) {
        this.bytesPerIndex = bytesPerIndex;
    }

    int bytesPerIndex() {
        return bytesPerIndex;
    }
}
