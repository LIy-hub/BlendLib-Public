package com.liy.blendlib.core.glb;

import com.liy.blendlib.core.json.JsonObject;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

/** Immutable, validated GLB 2.0 container split into JSON and BIN payloads. */
public final class GlbDocument {
    private final JsonObject json;
    private final byte[] binaryChunk;

    GlbDocument(JsonObject json, byte[] binaryChunk) {
        this.json = Objects.requireNonNull(json, "json");
        this.binaryChunk = Arrays.copyOf(Objects.requireNonNull(binaryChunk, "binaryChunk"), binaryChunk.length);
    }

    public JsonObject json() {
        return json;
    }

    public int binarySize() {
        return binaryChunk.length;
    }

    /** A read-only zero-copy view of the BIN payload. */
    public ByteBuffer binaryBuffer() {
        return ByteBuffer.wrap(binaryChunk).asReadOnlyBuffer();
    }

    /** Defensive-copy access for callers that need an array. */
    public byte[] binaryCopy() {
        return Arrays.copyOf(binaryChunk, binaryChunk.length);
    }
}
