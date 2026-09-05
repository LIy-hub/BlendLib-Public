package com.liy.blendlib.fabric.client.render.x7gpu;

import com.liy.blendlib.core.animation.runtime.CpuSkinnedMesh;
import com.liy.blendlib.core.animation.runtime.CpuSkinner;
import com.liy.blendlib.core.animation.runtime.PreparedSkinnedGeometry;
import com.liy.blendlib.core.animation.runtime.SkinPalette;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable 36-byte-per-vertex RED8I staging for the isolated T4 GPU-skinning candidate.
 *
 * <p>It validates the CPU reference predicates with the same double arithmetic before queue ownership, normalizes
 * influences on the CPU, precomputes a GPU-float parity proof, and only then exposes a direct little-endian upload
 * view.  A failed attempt is deliberately a CPU-selection result, never a partial GPU candidate.</p>
 */
final class X7SkinnedSourceTexelStaging implements AutoCloseable {
    static final int INFLUENCES_PER_VERTEX = 4;
    static final int JOINT_BYTES = INFLUENCES_PER_VERTEX * Short.BYTES;
    static final int WEIGHT_OFFSET_BYTES = JOINT_BYTES;
    static final int NORMAL_OFFSET_BYTES = WEIGHT_OFFSET_BYTES + INFLUENCES_PER_VERTEX * Float.BYTES;
    static final int RECORD_BYTES = NORMAL_OFFSET_BYTES + 3 * Float.BYTES;
    static final int MAX_VERTICES = 1_000_000;
    static final int MAX_SOURCE_BYTES = 36_000_000;
    static final double TOTAL_WEIGHT_EPSILON = 1.0e-8;
    static final double HOMOGENEOUS_W_EPSILON = 1.0e-12;
    static final double NORMAL_LENGTH_EPSILON = 1.0e-8;
    static final float GPU_PARITY_TOLERANCE = 1.0e-5F;

    private final Object exactGeometryIdentity;
    // Null only for the generation-lived static source path. That path owns geometry/influence bytes and deliberately
    // has no current-frame palette dependency.
    private final X7GpuSkinPaletteSnapshot exactPalette;
    private final int vertexCount;
    private final float[] cpuPositions;
    private final float[] cpuNormals;
    private final float[] gpuPositions;
    private final float[] gpuNormals;
    private ByteBuffer bytes;
    private boolean closed;

    private X7SkinnedSourceTexelStaging(
            Object exactGeometryIdentity,
            X7GpuSkinPaletteSnapshot exactPalette,
            int vertexCount,
            ByteBuffer bytes,
            float[] cpuPositions,
            float[] cpuNormals,
            float[] gpuPositions,
            float[] gpuNormals) {
        this.exactGeometryIdentity = exactGeometryIdentity;
        this.exactPalette = exactPalette;
        this.vertexCount = vertexCount;
        this.bytes = bytes;
        this.cpuPositions = cpuPositions;
        this.cpuNormals = cpuNormals;
        this.gpuPositions = gpuPositions;
        this.gpuNormals = gpuNormals;
    }

    static Attempt tryStage(PreparedSkinnedGeometry geometry, SkinPalette palette) {
        Objects.requireNonNull(geometry, "geometry");
        X7GpuSkinPaletteSnapshot.Attempt paletteAttempt = X7GpuSkinPaletteSnapshot.tryCapture(palette);
        if (!paletteAttempt.eligible()) {
            return Attempt.cpuFallback(paletteAttempt.fallbackCauseOrNull());
        }
        return tryStage(new PreparedGeometrySource(geometry), paletteAttempt.snapshotOrNull(), geometry, palette);
    }

    /**
     * Builds the generation-lived geometry/influence source stream without observing a transient frame palette.
     * Current-palette parity is proven separately by {@link #tryStage(PreparedSkinnedGeometry, SkinPalette)} before
     * a Stage-A palette unit may be admitted.
     */
    static StaticAttempt tryStageStatic(PreparedSkinnedGeometry geometry) {
        try {
            PreparedSkinnedGeometry checkedGeometry = Objects.requireNonNull(geometry, "geometry");
            return StaticAttempt.accepted(stageStatic(new PreparedGeometrySource(checkedGeometry), checkedGeometry));
        } catch (RuntimeException failure) {
            return StaticAttempt.cpuFallback(failure);
        }
    }

    /** Package-private raw-input seam permits exact epsilon fixtures without weakening the production geometry boundary. */
    static Attempt tryStage(GeometrySource source, X7GpuSkinPaletteSnapshot palette) {
        return tryStage(source, palette, source, null);
    }

    private static Attempt tryStage(
            GeometrySource source, X7GpuSkinPaletteSnapshot palette, Object exactGeometryIdentity, SkinPalette referencePalette) {
        try {
            GeometrySource checkedSource = Objects.requireNonNull(source, "source");
            X7GpuSkinPaletteSnapshot checkedPalette = Objects.requireNonNull(palette, "palette");
            return Attempt.accepted(stage(checkedSource, checkedPalette, exactGeometryIdentity, referencePalette));
        } catch (RuntimeException failure) {
            return Attempt.cpuFallback(failure);
        }
    }

    private static X7SkinnedSourceTexelStaging stage(
            GeometrySource source, X7GpuSkinPaletteSnapshot palette, Object exactGeometryIdentity, SkinPalette referencePalette) {
        int vertices = source.vertexCount();
        if (vertices <= 0 || vertices > MAX_VERTICES) {
            throw new IllegalArgumentException("GPU skin source vertex count is outside the frozen bounded range");
        }
        int byteCount = checkedByteCount(vertices);
        ByteBuffer packed = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.LITTLE_ENDIAN);
        float[] referencePositions = new float[Math.multiplyExact(vertices, 3)];
        float[] referenceNormals = new float[Math.multiplyExact(vertices, 3)];
        float[] simulatedPositions = new float[Math.multiplyExact(vertices, 3)];
        float[] simulatedNormals = new float[Math.multiplyExact(vertices, 3)];
        for (int vertex = 0; vertex < vertices; vertex++) {
            stageVertex(
                    source,
                    palette,
                    vertex,
                    packed,
                    referencePositions,
                    referenceNormals,
                    simulatedPositions,
                    simulatedNormals);
        }
        packed.flip();

        float[] cpuPositions;
        float[] cpuNormals;
        if (referencePalette != null && exactGeometryIdentity instanceof PreparedSkinnedGeometry geometry) {
            CpuSkinnedMesh reference = CpuSkinner.skin(geometry, referencePalette);
            cpuPositions = reference.positions();
            cpuNormals = reference.normals();
            requireWithinParityTolerance(cpuPositions, simulatedPositions, "position");
            requireWithinParityTolerance(cpuNormals, simulatedNormals, "normal");
        } else {
            // Raw epsilon fixtures have no legal core object to feed to CpuSkinner. The same CPU formulas were executed
            // in stageVertex before these GPU-float values were produced, so retain the proof vectors for inspection.
            cpuPositions = referencePositions;
            cpuNormals = referenceNormals;
        }
        return new X7SkinnedSourceTexelStaging(
                Objects.requireNonNull(exactGeometryIdentity, "exactGeometryIdentity"),
                palette,
                vertices,
                packed.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN),
                cpuPositions,
                cpuNormals,
                simulatedPositions,
                simulatedNormals);
    }

    private static X7SkinnedSourceTexelStaging stageStatic(GeometrySource source, PreparedSkinnedGeometry exactGeometry) {
        GeometrySource checkedSource = Objects.requireNonNull(source, "source");
        PreparedSkinnedGeometry checkedGeometry = Objects.requireNonNull(exactGeometry, "exactGeometry");
        int vertices = checkedSource.vertexCount();
        if (vertices <= 0 || vertices > MAX_VERTICES) {
            throw new IllegalArgumentException("GPU skin source vertex count is outside the frozen bounded range");
        }
        ByteBuffer packed = ByteBuffer.allocateDirect(checkedByteCount(vertices)).order(ByteOrder.LITTLE_ENDIAN);
        for (int vertex = 0; vertex < vertices; vertex++) {
            // Validate the immutable source values once for the generation owner. The palette-sensitive range and
            // parity proof stays in the current-frame path, so these source bytes never depend on a transient palette.
            finite(checkedSource.positionX(vertex), "positionX");
            finite(checkedSource.positionY(vertex), "positionY");
            finite(checkedSource.positionZ(vertex), "positionZ");
            float normalX = finite(checkedSource.normalX(vertex), "normalX");
            float normalY = finite(checkedSource.normalY(vertex), "normalY");
            float normalZ = finite(checkedSource.normalZ(vertex), "normalZ");
            int[] joints = new int[INFLUENCES_PER_VERTEX];
            float[] weights = new float[INFLUENCES_PER_VERTEX];
            double totalWeight = 0.0;
            for (int influence = 0; influence < INFLUENCES_PER_VERTEX; influence++) {
                int joint = checkedSource.joint(vertex, influence);
                if (joint < 0 || joint >= X7GpuSkinPaletteSnapshot.MAX_BONES) {
                    throw new IllegalArgumentException("GPU skin source joint is outside the frozen hardware range: " + joint);
                }
                float weight = checkedSource.weight(vertex, influence);
                if (!Float.isFinite(weight) || weight < 0.0F) {
                    throw new IllegalArgumentException("GPU skin source weights must be finite and non-negative");
                }
                joints[influence] = joint;
                weights[influence] = weight;
                totalWeight += weight;
            }
            if (!Double.isFinite(totalWeight) || totalWeight <= TOTAL_WEIGHT_EPSILON) {
                throw new IllegalArgumentException("GPU skinning requires finite total weight strictly greater than 1e-8");
            }
            for (int joint : joints) {
                packed.putShort((short) joint);
            }
            for (float weight : weights) {
                packed.putFloat(finite(weight / totalWeight, "normalized weight"));
            }
            packed.putFloat(normalX).putFloat(normalY).putFloat(normalZ);
        }
        packed.flip();
        return new X7SkinnedSourceTexelStaging(
                checkedGeometry,
                null,
                vertices,
                packed.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN),
                new float[0],
                new float[0],
                new float[0],
                new float[0]);
    }

    private static void stageVertex(
            GeometrySource source,
            X7GpuSkinPaletteSnapshot palette,
            int vertex,
            ByteBuffer packed,
            float[] referencePositions,
            float[] referenceNormals,
            float[] simulatedPositions,
            float[] simulatedNormals) {
        float sourcePositionX = finite(source.positionX(vertex), "positionX");
        float sourcePositionY = finite(source.positionY(vertex), "positionY");
        float sourcePositionZ = finite(source.positionZ(vertex), "positionZ");
        float sourceNormalX = finite(source.normalX(vertex), "normalX");
        float sourceNormalY = finite(source.normalY(vertex), "normalY");
        float sourceNormalZ = finite(source.normalZ(vertex), "normalZ");

        int[] joints = new int[INFLUENCES_PER_VERTEX];
        float[] weights = new float[INFLUENCES_PER_VERTEX];
        double totalWeight = 0.0;
        for (int influence = 0; influence < INFLUENCES_PER_VERTEX; influence++) {
            int joint = source.joint(vertex, influence);
            // The GPU source format has no sentinel and no alternate range. Validate every packed lane before it can
            // leave CPU memory, including a zero-weight lane, so corrupted bytes cannot select an arbitrary palette.
            if (joint < 0 || joint >= palette.jointCount() || joint >= X7GpuSkinPaletteSnapshot.MAX_BONES) {
                throw new IllegalArgumentException("GPU skin joint is outside the exact captured palette: " + joint);
            }
            float weight = source.weight(vertex, influence);
            if (!Float.isFinite(weight) || weight < 0.0F) {
                throw new IllegalArgumentException("GPU skin weights must be finite and non-negative");
            }
            joints[influence] = joint;
            weights[influence] = weight;
            totalWeight += weight;
        }
        if (!Double.isFinite(totalWeight) || totalWeight <= TOTAL_WEIGHT_EPSILON) {
            throw new IllegalArgumentException("GPU skinning requires finite total weight strictly greater than 1e-8");
        }

        float[] normalized = new float[INFLUENCES_PER_VERTEX];
        for (int influence = 0; influence < INFLUENCES_PER_VERTEX; influence++) {
            normalized[influence] = finite(weights[influence] / totalWeight, "normalized weight");
        }

        // This first pass deliberately repeats the core's double path so every accepted source has passed the exact
        // CPU predicate, including per-influence homogeneous division and the original normal determinant rule.
        CpuVector cpuPosition = new CpuVector();
        CpuVector cpuNormal = new CpuVector();
        for (int influence = 0; influence < INFLUENCES_PER_VERTEX; influence++) {
            if (weights[influence] <= 0.0F) {
                continue;
            }
            int joint = joints[influence];
            float[] positionMatrix = palette.positionMatrixCopy(joint);
            double pointX = positionMatrix[0] * sourcePositionX + positionMatrix[4] * sourcePositionY
                    + positionMatrix[8] * sourcePositionZ + positionMatrix[12];
            double pointY = positionMatrix[1] * sourcePositionX + positionMatrix[5] * sourcePositionY
                    + positionMatrix[9] * sourcePositionZ + positionMatrix[13];
            double pointZ = positionMatrix[2] * sourcePositionX + positionMatrix[6] * sourcePositionY
                    + positionMatrix[10] * sourcePositionZ + positionMatrix[14];
            double w = positionMatrix[3] * sourcePositionX + positionMatrix[7] * sourcePositionY
                    + positionMatrix[11] * sourcePositionZ + positionMatrix[15];
            if (!Double.isFinite(w) || Math.abs(w) <= HOMOGENEOUS_W_EPSILON) {
                throw new IllegalArgumentException("GPU skinning requires finite homogeneous abs(w) > 1e-12");
            }
            float transformedX = finite(pointX / w, "transformed position X");
            float transformedY = finite(pointY / w, "transformed position Y");
            float transformedZ = finite(pointZ / w, "transformed position Z");
            float[] transformedNormal = transformNormalExactly(
                    positionMatrix, sourceNormalX, sourceNormalY, sourceNormalZ);
            cpuPosition.add(weights[influence], transformedX, transformedY, transformedZ);
            cpuNormal.add(weights[influence], transformedNormal[0], transformedNormal[1], transformedNormal[2]);
        }
        // These finite conversions exactly mirror CpuSkinner's post-accumulation conversion points.
        int outputOffset = vertex * 3;
        referencePositions[outputOffset] = finite(cpuPosition.x / totalWeight, "CPU reference position X");
        referencePositions[outputOffset + 1] = finite(cpuPosition.y / totalWeight, "CPU reference position Y");
        referencePositions[outputOffset + 2] = finite(cpuPosition.z / totalWeight, "CPU reference position Z");
        float[] referenceNormal = normalOrZero(cpuNormal.x, cpuNormal.y, cpuNormal.z, "CPU reference normal");
        referenceNormals[outputOffset] = referenceNormal[0];
        referenceNormals[outputOffset + 1] = referenceNormal[1];
        referenceNormals[outputOffset + 2] = referenceNormal[2];

        float gpuPositionX = 0.0F;
        float gpuPositionY = 0.0F;
        float gpuPositionZ = 0.0F;
        float gpuNormalX = 0.0F;
        float gpuNormalY = 0.0F;
        float gpuNormalZ = 0.0F;
        for (int influence = 0; influence < INFLUENCES_PER_VERTEX; influence++) {
            if (normalized[influence] <= 0.0F) {
                continue;
            }
            int joint = joints[influence];
            float[] matrix = palette.positionMatrixCopy(joint);
            float w = matrix[3] * sourcePositionX + matrix[7] * sourcePositionY + matrix[11] * sourcePositionZ + matrix[15];
            // The exact double predicate was already proven above; this guard prevents a float underflow from becoming
            // a shader divide-by-zero approximation. Such a record remains CPU-only.
            if (!Float.isFinite(w) || Math.abs((double) w) <= HOMOGENEOUS_W_EPSILON) {
                throw new IllegalArgumentException("GPU float homogeneous proof cannot preserve CPU eligibility");
            }
            float transformedX = (matrix[0] * sourcePositionX + matrix[4] * sourcePositionY
                    + matrix[8] * sourcePositionZ + matrix[12]) / w;
            float transformedY = (matrix[1] * sourcePositionX + matrix[5] * sourcePositionY
                    + matrix[9] * sourcePositionZ + matrix[13]) / w;
            float transformedZ = (matrix[2] * sourcePositionX + matrix[6] * sourcePositionY
                    + matrix[10] * sourcePositionZ + matrix[14]) / w;
            float[] normal = palette.normalMatrixCopy(joint);
            gpuPositionX += normalized[influence] * transformedX;
            gpuPositionY += normalized[influence] * transformedY;
            gpuPositionZ += normalized[influence] * transformedZ;
            gpuNormalX += normalized[influence]
                    * (normal[0] * sourceNormalX + normal[4] * sourceNormalY + normal[8] * sourceNormalZ);
            gpuNormalY += normalized[influence]
                    * (normal[1] * sourceNormalX + normal[5] * sourceNormalY + normal[9] * sourceNormalZ);
            gpuNormalZ += normalized[influence]
                    * (normal[2] * sourceNormalX + normal[6] * sourceNormalY + normal[10] * sourceNormalZ);
        }
        float[] gpuNormal = normalOrZero(gpuNormalX, gpuNormalY, gpuNormalZ, "GPU parity normal");
        requireFinite(gpuPositionX, "GPU parity position X");
        requireFinite(gpuPositionY, "GPU parity position Y");
        requireFinite(gpuPositionZ, "GPU parity position Z");
        simulatedPositions[outputOffset] = gpuPositionX;
        simulatedPositions[outputOffset + 1] = gpuPositionY;
        simulatedPositions[outputOffset + 2] = gpuPositionZ;
        simulatedNormals[outputOffset] = gpuNormal[0];
        simulatedNormals[outputOffset + 1] = gpuNormal[1];
        simulatedNormals[outputOffset + 2] = gpuNormal[2];

        for (int influence = 0; influence < INFLUENCES_PER_VERTEX; influence++) {
            packed.putShort((short) joints[influence]);
        }
        for (float weight : normalized) {
            packed.putFloat(weight);
        }
        packed.putFloat(sourceNormalX).putFloat(sourceNormalY).putFloat(sourceNormalZ);
    }

    /** Repeats {@link SkinPalette}'s private inverse-transpose arithmetic before any GPU-float approximation. */
    private static float[] transformNormalExactly(float[] matrix, float normalX, float normalY, float normalZ) {
        double a00 = matrix[0];
        double a01 = matrix[4];
        double a02 = matrix[8];
        double a10 = matrix[1];
        double a11 = matrix[5];
        double a12 = matrix[9];
        double a20 = matrix[2];
        double a21 = matrix[6];
        double a22 = matrix[10];
        double c00 = a11 * a22 - a12 * a21;
        double c01 = a12 * a20 - a10 * a22;
        double c02 = a10 * a21 - a11 * a20;
        double c10 = a02 * a21 - a01 * a22;
        double c11 = a00 * a22 - a02 * a20;
        double c12 = a01 * a20 - a00 * a21;
        double c20 = a01 * a12 - a02 * a11;
        double c21 = a02 * a10 - a00 * a12;
        double c22 = a00 * a11 - a01 * a10;
        double determinant = a00 * c00 + a01 * c10 + a02 * c20;
        if (!Double.isFinite(determinant) || Math.abs(determinant) <= HOMOGENEOUS_W_EPSILON) {
            throw new IllegalArgumentException("GPU skinning requires finite normal abs(det) > 1e-12");
        }
        return new float[] {
            finite((c00 * normalX + c01 * normalY + c02 * normalZ) / determinant, "transformed normal X"),
            finite((c10 * normalX + c11 * normalY + c12 * normalZ) / determinant, "transformed normal Y"),
            finite((c20 * normalX + c21 * normalY + c22 * normalZ) / determinant, "transformed normal Z")
        };
    }

    private static int checkedByteCount(int vertices) {
        long count = Math.multiplyExact((long) vertices, RECORD_BYTES);
        if (count > MAX_SOURCE_BYTES || count > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("GPU skin source staging exceeds the frozen 36 MiB bound");
        }
        return (int) count;
    }

    private static float finite(double value, String label) {
        float result = (float) value;
        if (!Double.isFinite(value) || !Float.isFinite(result)) {
            throw new IllegalArgumentException(label + " must be finite");
        }
        return result;
    }

    private static float finite(float value, String label) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(label + " must be finite");
        }
        return value;
    }

    private static void requireFinite(float value, String label) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(label + " must be finite");
        }
    }

    private static float[] normalOrZero(double x, double y, double z, String label) {
        double length = Math.sqrt(x * x + y * y + z * z);
        if (!Double.isFinite(length) || length <= NORMAL_LENGTH_EPSILON) {
            return new float[] {0.0F, 0.0F, 0.0F};
        }
        return new float[] {finite(x / length, label + " X"), finite(y / length, label + " Y"), finite(z / length, label + " Z")};
    }

    private static float[] normalOrZero(float x, float y, float z, String label) {
        float length = (float) Math.sqrt(x * x + y * y + z * z);
        if (!Float.isFinite(length) || length <= NORMAL_LENGTH_EPSILON) {
            return new float[] {0.0F, 0.0F, 0.0F};
        }
        float normalX = x / length;
        float normalY = y / length;
        float normalZ = z / length;
        requireFinite(normalX, label + " X");
        requireFinite(normalY, label + " Y");
        requireFinite(normalZ, label + " Z");
        return new float[] {normalX, normalY, normalZ};
    }

    private static void requireWithinParityTolerance(float[] cpu, float[] gpu, String kind) {
        if (cpu.length != gpu.length) {
            throw new IllegalArgumentException("GPU skin parity proof changed " + kind + " cardinality");
        }
        for (int component = 0; component < cpu.length; component++) {
            if (!Float.isFinite(cpu[component]) || !Float.isFinite(gpu[component])
                    || Math.abs(cpu[component] - gpu[component]) > GPU_PARITY_TOLERANCE) {
                throw new IllegalArgumentException("GPU float skinning cannot prove CPU " + kind + " parity");
            }
        }
    }

    Object exactGeometryIdentity() {
        return exactGeometryIdentity;
    }

    X7GpuSkinPaletteSnapshot exactPalette() {
        if (exactPalette == null) {
            throw new IllegalStateException("The generation-lived static source has no transient palette");
        }
        return exactPalette;
    }

    int vertexCount() {
        return vertexCount;
    }

    int sourceByteCount() {
        return Math.multiplyExact(vertexCount, RECORD_BYTES);
    }

    boolean matchesExactly(Object geometryIdentity, X7GpuSkinPaletteSnapshot palette) {
        return exactGeometryIdentity == geometryIdentity && exactPalette == palette;
    }

    ByteBuffer bytesForUpload() {
        requireOpen();
        ByteBuffer copy = bytes.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
        copy.position(0);
        return copy;
    }

    float[] cpuPositionsForTest() {
        return Arrays.copyOf(cpuPositions, cpuPositions.length);
    }

    float[] cpuNormalsForTest() {
        return Arrays.copyOf(cpuNormals, cpuNormals.length);
    }

    float[] gpuPositionsForTest() {
        return Arrays.copyOf(gpuPositions, gpuPositions.length);
    }

    float[] gpuNormalsForTest() {
        return Arrays.copyOf(gpuNormals, gpuNormals.length);
    }

    boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        bytes = null;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("GPU skin source staging has already been released");
        }
    }

    interface GeometrySource {
        int vertexCount();

        float positionX(int vertex);

        float positionY(int vertex);

        float positionZ(int vertex);

        float normalX(int vertex);

        float normalY(int vertex);

        float normalZ(int vertex);

        int joint(int vertex, int influence);

        float weight(int vertex, int influence);
    }

    private static final class PreparedGeometrySource implements GeometrySource {
        private final PreparedSkinnedGeometry geometry;
        private final float[] positions;
        private final float[] normals;
        private final int[] joints;
        private final float[] weights;

        private PreparedGeometrySource(PreparedSkinnedGeometry geometry) {
            this.geometry = geometry;
            this.positions = geometry.positions();
            this.normals = geometry.normals();
            this.joints = geometry.joints();
            this.weights = geometry.weights();
        }

        @Override
        public int vertexCount() {
            return geometry.vertexCount();
        }

        @Override
        public float positionX(int vertex) {
            return positions[vertex * 3];
        }

        @Override
        public float positionY(int vertex) {
            return positions[vertex * 3 + 1];
        }

        @Override
        public float positionZ(int vertex) {
            return positions[vertex * 3 + 2];
        }

        @Override
        public float normalX(int vertex) {
            return normals[vertex * 3];
        }

        @Override
        public float normalY(int vertex) {
            return normals[vertex * 3 + 1];
        }

        @Override
        public float normalZ(int vertex) {
            return normals[vertex * 3 + 2];
        }

        @Override
        public int joint(int vertex, int influence) {
            return joints[vertex * INFLUENCES_PER_VERTEX + influence];
        }

        @Override
        public float weight(int vertex, int influence) {
            return weights[vertex * INFLUENCES_PER_VERTEX + influence];
        }
    }

    private static final class CpuVector {
        private double x;
        private double y;
        private double z;

        private void add(float weight, float x, float y, float z) {
            this.x += weight * x;
            this.y += weight * y;
            this.z += weight * z;
        }
    }

    static final class Attempt {
        private final X7SkinnedSourceTexelStaging staging;
        private final Throwable fallbackCause;

        private Attempt(X7SkinnedSourceTexelStaging staging, Throwable fallbackCause) {
            this.staging = staging;
            this.fallbackCause = fallbackCause;
        }

        private static Attempt accepted(X7SkinnedSourceTexelStaging staging) {
            return new Attempt(Objects.requireNonNull(staging, "staging"), null);
        }

        private static Attempt cpuFallback(Throwable failure) {
            return new Attempt(null, Objects.requireNonNull(failure, "failure"));
        }

        boolean eligible() {
            return staging != null;
        }

        X7SkinnedSourceTexelStaging stagingOrNull() {
            return staging;
        }

        Throwable fallbackCauseOrNull() {
            return fallbackCause;
        }
    }

    /** Generation-only static source result; it retains no palette and has no raw-byte factory overload. */
    static final class StaticAttempt {
        private final X7SkinnedSourceTexelStaging staging;
        private final Throwable fallbackCause;

        private StaticAttempt(X7SkinnedSourceTexelStaging staging, Throwable fallbackCause) {
            this.staging = staging;
            this.fallbackCause = fallbackCause;
        }

        private static StaticAttempt accepted(X7SkinnedSourceTexelStaging staging) {
            return new StaticAttempt(Objects.requireNonNull(staging, "staging"), null);
        }

        private static StaticAttempt cpuFallback(Throwable failure) {
            return new StaticAttempt(null, Objects.requireNonNull(failure, "failure"));
        }

        boolean eligible() {
            return staging != null;
        }

        X7SkinnedSourceTexelStaging stagingOrNull() {
            return staging;
        }

        Throwable fallbackCauseOrNull() {
            return fallbackCause;
        }
    }
}
