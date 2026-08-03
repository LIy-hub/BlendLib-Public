package com.liy.blendlib.core.glb;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.asset.AssetBytes;
import com.liy.blendlib.core.diagnostic.BlendAssetLoadException;
import com.liy.blendlib.core.diagnostic.BlendDiagnostic;
import com.liy.blendlib.core.diagnostic.BlendDiagnosticCodes;
import com.liy.blendlib.core.json.JsonObject;
import com.liy.blendlib.core.json.StrictJsonParser;
import com.liy.blendlib.core.limits.BlendAssetLimits;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;

/** Strict, resource-agnostic GLB 2.0 container reader. */
public final class GlbReader {
    private static final int GLB_MAGIC = 0x46546C67;
    private static final int GLB_VERSION = 2;
    private static final int JSON_CHUNK_TYPE = 0x4E4F534A;
    private static final int BIN_CHUNK_TYPE = 0x004E4942;

    private final BlendAssetLimits limits;

    public GlbReader() {
        this(BlendAssetLimits.DEFAULT);
    }

    public GlbReader(BlendAssetLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /** Parses a GLB archive from already-loaded bytes. */
    public GlbDocument read(BlendResourceId modelKey, AssetBytes glbBytes) {
        Objects.requireNonNull(modelKey, "modelKey");
        Objects.requireNonNull(glbBytes, "glbBytes");
        if (glbBytes.size() > limits.maxGlbBytes()) {
            throw failure(BlendDiagnosticCodes.LIMIT_001, modelKey, glbBytes.resourceId(), "", "GLB byte limit exceeded", null);
        }
        byte[] input = glbBytes.copy();
        if (input.length < 12) {
            throw failure(BlendDiagnosticCodes.GLB_001, modelKey, glbBytes.resourceId(), "", "GLB header is truncated", null);
        }
        ByteBuffer header = ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN);
        int magic = header.getInt(0);
        int version = header.getInt(4);
        long declaredLength = Integer.toUnsignedLong(header.getInt(8));
        if (magic != GLB_MAGIC || version != GLB_VERSION || declaredLength != input.length) {
            throw failure(BlendDiagnosticCodes.GLB_001, modelKey, glbBytes.resourceId(), "", "Invalid GLB header or declared length", null);
        }

        int cursor = 12;
        byte[] jsonChunk = null;
        byte[] binaryChunk = null;
        int chunkCount = 0;
        while (cursor < input.length) {
            if ((long) cursor + 8L > input.length) {
                throw failure(BlendDiagnosticCodes.GLB_002, modelKey, glbBytes.resourceId(), "", "GLB chunk header is truncated", null);
            }
            long chunkLength = Integer.toUnsignedLong(header.getInt(cursor));
            int chunkType = header.getInt(cursor + 4);
            long dataStart = (long) cursor + 8L;
            long dataEnd = dataStart + chunkLength;
            if ((chunkLength & 3L) != 0L || dataEnd > input.length || dataEnd < dataStart) {
                throw failure(BlendDiagnosticCodes.GLB_002, modelKey, glbBytes.resourceId(), "", "GLB chunk exceeds archive bounds", null);
            }
            if (chunkCount == 0) {
                if (chunkType != JSON_CHUNK_TYPE) {
                    throw failure(BlendDiagnosticCodes.GLB_002, modelKey, glbBytes.resourceId(), "", "First GLB chunk must be JSON", null);
                }
                jsonChunk = Arrays.copyOfRange(input, (int) dataStart, (int) dataEnd);
            } else if (chunkCount == 1 && chunkType == BIN_CHUNK_TYPE) {
                binaryChunk = Arrays.copyOfRange(input, (int) dataStart, (int) dataEnd);
            } else {
                throw failure(BlendDiagnosticCodes.GLB_002, modelKey, glbBytes.resourceId(), "", "GLB contains an unsupported chunk layout", null);
            }
            chunkCount++;
            cursor = (int) dataEnd;
        }
        if (cursor != input.length || jsonChunk == null) {
            throw failure(BlendDiagnosticCodes.GLB_002, modelKey, glbBytes.resourceId(), "", "GLB is missing a JSON chunk", null);
        }
        if (binaryChunk == null) {
            binaryChunk = new byte[0];
        }
        try {
            if (!(StrictJsonParser.parse(jsonChunk) instanceof JsonObject json)) {
                throw new IllegalArgumentException("GLB JSON root must be an object");
            }
            return new GlbDocument(json, binaryChunk);
        } catch (RuntimeException exception) {
            throw failure(BlendDiagnosticCodes.GLB_002, modelKey, glbBytes.resourceId(), "", "GLB JSON chunk is invalid", exception);
        }
    }

    private static BlendAssetLoadException failure(
            String code, BlendResourceId modelKey, BlendResourceId resourceId, String location, String message, Throwable cause) {
        BlendDiagnostic diagnostic = BlendDiagnostic.error(code, modelKey, resourceId, location, message);
        return cause == null ? new BlendAssetLoadException(diagnostic) : new BlendAssetLoadException(diagnostic, cause);
    }
}
