package com.liy.blendlib.fabric.client.reload;

import java.nio.ByteBuffer;

/** Narrow test seam for the adapter-private typed 26.1.2 buffer allocation path. */
interface X7GpuDevice {
    void assertOnRenderThread();

    X7GpuBuffer allocateVertexUpload(String debugLabel, ByteBuffer bytes);

    X7GpuBuffer allocateIndexUpload(String debugLabel, ByteBuffer bytes);
}
