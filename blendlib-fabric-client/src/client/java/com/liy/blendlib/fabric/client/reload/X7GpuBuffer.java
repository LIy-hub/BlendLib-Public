package com.liy.blendlib.fabric.client.reload;

/** Small ownership wrapper; it intentionally has no device-close operation. */
interface X7GpuBuffer extends AutoCloseable {
    boolean isClosed();

    @Override
    void close();
}
