package com.liy.blendlib.core.asset;

import com.liy.blendlib.api.BlendResourceId;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

/** Immutable byte payload returned by a resource-agnostic asset resolver. */
public final class AssetBytes {
    private final BlendResourceId resourceId;
    private final byte[] bytes;

    public AssetBytes(BlendResourceId resourceId, byte[] bytes) {
        this.resourceId = Objects.requireNonNull(resourceId, "resourceId");
        this.bytes = Arrays.copyOf(Objects.requireNonNull(bytes, "bytes"), bytes.length);
    }

    public BlendResourceId resourceId() {
        return resourceId;
    }

    public int size() {
        return bytes.length;
    }

    /** Returns a defensive copy for APIs that require a byte array. */
    public byte[] copy() {
        return Arrays.copyOf(bytes, bytes.length);
    }

    /** Returns a zero-copy, read-only view whose position is always zero. */
    public ByteBuffer readOnlyBuffer() {
        return ByteBuffer.wrap(bytes).asReadOnlyBuffer();
    }
}
