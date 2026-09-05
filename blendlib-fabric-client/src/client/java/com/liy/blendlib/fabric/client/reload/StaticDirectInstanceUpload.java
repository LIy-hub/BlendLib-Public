package com.liy.blendlib.fabric.client.reload;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Objects;
import org.joml.Matrix3fc;
import org.joml.Matrix4fc;
import org.joml.Vector4fc;

/** Immutable std140 instance payload for one bounded direct-static batch. */
final class StaticDirectInstanceUpload {
    static final int MODEL_VIEW_BYTES = 16 * Float.BYTES;
    static final int NORMAL_MATRIX_BYTES = 3 * 4 * Float.BYTES;
    static final int COLOR_BYTES = 4 * Float.BYTES;
    static final int PACKED_LIGHT_BYTES = 4 * Float.BYTES;
    static final int INSTANCE_STRIDE_BYTES = MODEL_VIEW_BYTES + NORMAL_MATRIX_BYTES + COLOR_BYTES + PACKED_LIGHT_BYTES;

    private final int instanceCount;
    private final ByteBuffer bytes;

    private StaticDirectInstanceUpload(int instanceCount, ByteBuffer bytes) {
        this.instanceCount = instanceCount;
        this.bytes = bytes;
    }

    static StaticDirectInstanceUpload pack(List<StaticDirectFrameInputs> inputs) {
        List<StaticDirectFrameInputs> checkedInputs = List.copyOf(Objects.requireNonNull(inputs, "inputs"));
        if (checkedInputs.isEmpty() || checkedInputs.size() > StaticDirectBatch.MAX_INSTANCES) {
            throw new IllegalArgumentException("The direct-static instance upload must use the frozen bounded batch size");
        }
        ByteBuffer bytes = ByteBuffer.allocateDirect(Math.multiplyExact(checkedInputs.size(), INSTANCE_STRIDE_BYTES))
                .order(ByteOrder.LITTLE_ENDIAN);
        for (StaticDirectFrameInputs input : checkedInputs) {
            StaticDirectFrameInputs checkedInput = Objects.requireNonNull(input, "input");
            putMatrix4(bytes, checkedInput.modelViewCopy());
            putNormalMatrix3Std140(bytes, checkedInput.normalTransformCopy());
            putVector4(bytes, checkedInput.colorModulatorCopy());
            putPackedLight(bytes, checkedInput.packedLight());
        }
        bytes.flip();
        return new StaticDirectInstanceUpload(checkedInputs.size(), bytes.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN));
    }

    int instanceCount() {
        return instanceCount;
    }

    int byteSize() {
        return bytes.limit();
    }

    ByteBuffer bytesForUpload() {
        ByteBuffer copy = bytes.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
        copy.position(0);
        return copy;
    }

    private static void putMatrix4(ByteBuffer destination, Matrix4fc matrix) {
        destination.putFloat(matrix.m00()).putFloat(matrix.m01()).putFloat(matrix.m02()).putFloat(matrix.m03());
        destination.putFloat(matrix.m10()).putFloat(matrix.m11()).putFloat(matrix.m12()).putFloat(matrix.m13());
        destination.putFloat(matrix.m20()).putFloat(matrix.m21()).putFloat(matrix.m22()).putFloat(matrix.m23());
        destination.putFloat(matrix.m30()).putFloat(matrix.m31()).putFloat(matrix.m32()).putFloat(matrix.m33());
    }

    private static void putNormalMatrix3Std140(ByteBuffer destination, Matrix3fc matrix) {
        destination.putFloat(matrix.m00()).putFloat(matrix.m01()).putFloat(matrix.m02()).putFloat(0.0F);
        destination.putFloat(matrix.m10()).putFloat(matrix.m11()).putFloat(matrix.m12()).putFloat(0.0F);
        destination.putFloat(matrix.m20()).putFloat(matrix.m21()).putFloat(matrix.m22()).putFloat(0.0F);
    }

    private static void putVector4(ByteBuffer destination, Vector4fc vector) {
        destination.putFloat(vector.x()).putFloat(vector.y()).putFloat(vector.z()).putFloat(vector.w());
    }

    private static void putPackedLight(ByteBuffer destination, int packedLight) {
        // Each 16-bit lane is stored as an exactly representable integer float; the shader converts it back to ivec2
        // before invoking vanilla sample_lightmap(Sampler2, uv).
        destination.putFloat(packedLight & 0xFFFF)
                .putFloat(packedLight >>> 16 & 0xFFFF)
                .putFloat(0.0F)
                .putFloat(0.0F);
    }
}
