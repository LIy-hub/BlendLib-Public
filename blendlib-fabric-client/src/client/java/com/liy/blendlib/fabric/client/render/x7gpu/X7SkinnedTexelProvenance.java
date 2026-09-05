package com.liy.blendlib.fabric.client.render.x7gpu;

import com.liy.blendlib.core.animation.runtime.PreparedSkinnedGeometry;
import com.liy.blendlib.core.animation.runtime.SkinnedMeshTopology;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Materialized T4p upload proof for one exact extraction-frame primitive.
 *
 * <p>This carrier is the current-frame half of the T4p proof: it owns only the validated typed palette/parity unit
 * and retains the exact immutable prepared geometry identity. The generation-lived vertex/index/influence source is
 * separately owned by {@link StaticSource} inside D1. Thus a later current palette can match the same D1 geometry
 * source without recreating or owning static source bytes.</p>
 */
public final class X7SkinnedTexelProvenance implements AutoCloseable {
    public static final int VERTEX_STRIDE_BYTES = 8 * Float.BYTES;
    public static final int SOURCE_RECORD_BYTES = X7SkinnedSourceTexelStaging.RECORD_BYTES;
    public static final int MAX_VERTICES = X7SkinnedSourceTexelStaging.MAX_VERTICES;
    public static final int MAX_INDICES = 3_000_000;
    public static final int MAX_VERTEX_BYTES = 32_000_000;
    public static final int MAX_INDEX_BYTES = 12_000_000;
    public static final int MAX_SOURCE_BYTES = X7SkinnedSourceTexelStaging.MAX_SOURCE_BYTES;

    private final X7SkinnedFrameProvenance frame;
    private final PreparedSkinnedGeometry exactGeometry;
    private final int vertexCount;
    private final int indexCount;
    private final int vertexByteCount;
    private final int indexByteCount;
    private final int sourceByteCount;
    private PaletteUpload paletteUpload;
    private boolean closed;

    private X7SkinnedTexelProvenance(
            X7SkinnedFrameProvenance frame,
            PaletteUpload paletteUpload) {
        this.frame = Objects.requireNonNull(frame, "frame");
        this.exactGeometry = frame.exactGeometry();
        this.vertexCount = exactGeometry.vertexCount();
        this.indexCount = exactGeometry.topology().indexCount();
        this.vertexByteCount = checkedByteCount(vertexCount, VERTEX_STRIDE_BYTES, MAX_VERTEX_BYTES, "vertex");
        this.indexByteCount = checkedByteCount(indexCount, Integer.BYTES, MAX_INDEX_BYTES, "index");
        this.sourceByteCount = checkedByteCount(vertexCount, SOURCE_RECORD_BYTES, MAX_SOURCE_BYTES, "source");
        if (vertexCount <= 0 || indexCount <= 0 || indexCount % 3 != 0) {
            throw new IllegalArgumentException("Validated current T4p frame lost its exact geometry cardinality");
        }
        this.paletteUpload = Objects.requireNonNull(paletteUpload, "paletteUpload");
    }

    /** Package-private materializer: only the T4p frame proof can trigger validated GPU upload staging. */
    static Attempt tryMaterialize(X7SkinnedFrameProvenance frame) {
        X7SkinnedFrameProvenance checkedFrame = Objects.requireNonNull(frame, "frame");
        X7SkinnedSourceTexelStaging source = null;
        PaletteUpload paletteUpload = null;
        boolean published = false;
        try {
            X7SkinnedSourceTexelStaging.Attempt sourceAttempt = X7SkinnedSourceTexelStaging.tryStage(
                    checkedFrame.exactGeometry(), checkedFrame.exactPalette());
            if (!sourceAttempt.eligible()) {
                return Attempt.cpuFallback(sourceAttempt.fallbackCauseOrNull());
            }
            source = sourceAttempt.stagingOrNull();
            if (source.exactGeometryIdentity() != checkedFrame.exactGeometry()
                    || !source.exactPalette().matchesExactly(checkedFrame.exactPalette())) {
                throw new IllegalStateException("T4p source staging did not retain the exact extraction geometry and palette");
            }
            paletteUpload = PaletteUpload.capture(checkedFrame, source.exactPalette());
            // The parity staging is transient; its direct source bytes are not the generation owner. The typed
            // palette unit now owns the palette snapshot, while D1 obtains static bytes through StaticSource.
            source.close();
            source = null;
            X7SkinnedTexelProvenance result = new X7SkinnedTexelProvenance(checkedFrame, paletteUpload);
            published = true;
            return Attempt.accepted(result);
        } catch (RuntimeException failure) {
            return Attempt.cpuFallback(failure);
        } finally {
            if (!published) {
                if (paletteUpload != null) {
                    paletteUpload.close();
                } else if (source != null) {
                    // Source staging observes the palette but does not own its direct 8 KiB snapshot.
                    source.exactPalette().close();
                }
                if (source != null) {
                    source.close();
                }
            }
        }
    }

    /** True only for the one exact extraction proof from which every upload stream was derived. */
    public boolean matchesExactly(X7SkinnedFrameProvenance candidate) {
        return frame == Objects.requireNonNull(candidate, "candidate");
    }

    /** True only when a D1 static source retains this exact prepared geometry identity. */
    public boolean matchesStaticGeometry(PreparedSkinnedGeometry candidate) {
        return exactGeometry == Objects.requireNonNull(candidate, "candidate");
    }

    public int vertexCount() {
        return vertexCount;
    }

    public int indexCount() {
        return indexCount;
    }

    public int vertexByteCount() {
        return vertexByteCount;
    }

    public int indexByteCount() {
        return indexByteCount;
    }

    public int sourceByteCount() {
        return sourceByteCount;
    }

    /** The unforgeable typed palette byte-and-proof unit for this exact extraction frame. */
    public synchronized PaletteUpload paletteUpload() {
        requireOpen();
        return paletteUpload;
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        PaletteUpload ownedPalette = paletteUpload;
        paletteUpload = null;
        if (ownedPalette != null) {
            ownedPalette.close();
        }
    }

    private static ByteBuffer readOnlyLittleEndian(ByteBuffer bytes) {
        ByteBuffer copy = bytes.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
        copy.position(0);
        return copy;
    }

    private void requireOpen() {
        if (closed || paletteUpload == null) {
            throw new IllegalStateException("The exact T4p skinned upload proof has already been released");
        }
    }

    private static int checkedByteCount(int count, int width, int maximum, String label) {
        long bytes = Math.multiplyExact((long) count, (long) width);
        if (bytes > maximum || bytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("T4p skinned " + label + " bytes exceed the frozen device bound");
        }
        return (int) bytes;
    }

    private static float finite(float value, String label) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("T4p skinned " + label + " must be finite");
        }
        return value;
    }

    /**
     * Generation-lived exact geometry/influence/source owner. It is created only from the sealed prepared geometry
     * identity, never from a frame palette; D1 keeps this object until native resource close succeeds.
     */
    public static final class StaticSource implements AutoCloseable {
        private final PreparedSkinnedGeometry exactGeometry;
        private final int vertexCount;
        private final int indexCount;
        private final int vertexByteCount;
        private final int indexByteCount;
        private final int sourceByteCount;
        private X7SkinnedSourceTexelStaging source;
        private PackedGeometry geometry;
        private boolean closed;

        private StaticSource(
                PreparedSkinnedGeometry exactGeometry,
                X7SkinnedSourceTexelStaging source,
                PackedGeometry geometry) {
            this.exactGeometry = Objects.requireNonNull(exactGeometry, "exactGeometry");
            this.source = Objects.requireNonNull(source, "source");
            this.geometry = Objects.requireNonNull(geometry, "geometry");
            this.vertexCount = geometry.vertexCount;
            this.indexCount = geometry.indexCount;
            this.vertexByteCount = geometry.vertexByteCount;
            this.indexByteCount = geometry.indexByteCount;
            this.sourceByteCount = source.sourceByteCount();
            if (source.exactGeometryIdentity() != exactGeometry
                    || vertexCount != source.vertexCount()
                    || sourceByteCount != checkedByteCount(
                            vertexCount, SOURCE_RECORD_BYTES, MAX_SOURCE_BYTES, "source")) {
                throw new IllegalArgumentException("The D1 static source lost its exact geometry/influence identity");
            }
        }

        /**
         * D1 generation entrypoint. The current frame proof validates only that its immutable geometry identity is
         * the source being published; no palette bytes or palette object are accepted here.
         */
        public static StaticSource captureForGeneration(X7SkinnedFrameProvenance frame) {
            X7SkinnedFrameProvenance checkedFrame = Objects.requireNonNull(frame, "frame");
            X7SkinnedSourceTexelStaging source = null;
            PackedGeometry geometry = null;
            try {
                X7SkinnedSourceTexelStaging.StaticAttempt sourceAttempt =
                        X7SkinnedSourceTexelStaging.tryStageStatic(checkedFrame.exactGeometry());
                if (!sourceAttempt.eligible()) {
                    throw new IllegalArgumentException(
                            "The exact D1 static source is GPU-ineligible", sourceAttempt.fallbackCauseOrNull());
                }
                source = Objects.requireNonNull(sourceAttempt.stagingOrNull(), "static source staging");
                geometry = PackedGeometry.fromExactPreparedGeometry(checkedFrame.exactGeometry());
                return new StaticSource(checkedFrame.exactGeometry(), source, geometry);
            } catch (Throwable failure) {
                if (geometry != null) {
                    geometry.close();
                }
                if (source != null) {
                    source.close();
                }
                rethrowUnchecked(failure);
                throw new AssertionError("unreachable");
            }
        }

        /** Matches only the immutable exact geometry identity sealed into the current frame proof. */
        public synchronized boolean matchesExactly(X7SkinnedTexelProvenance currentFrame) {
            requireOpen();
            return Objects.requireNonNull(currentFrame, "currentFrame").matchesStaticGeometry(exactGeometry);
        }

        public synchronized int vertexCount() {
            requireOpen();
            return vertexCount;
        }

        public synchronized int indexCount() {
            requireOpen();
            return indexCount;
        }

        public synchronized int vertexByteCount() {
            requireOpen();
            return vertexByteCount;
        }

        public synchronized int indexByteCount() {
            requireOpen();
            return indexByteCount;
        }

        public synchronized int sourceByteCount() {
            requireOpen();
            return sourceByteCount;
        }

        public synchronized ByteBuffer vertexBytesForD1Upload() {
            requireOpen();
            return readOnlyLittleEndian(geometry.vertexBytes);
        }

        public synchronized ByteBuffer indexBytesForD1Upload() {
            requireOpen();
            return readOnlyLittleEndian(geometry.indexBytes);
        }

        public synchronized ByteBuffer sourceBytesForD1Upload() {
            requireOpen();
            return source.bytesForUpload();
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            X7SkinnedSourceTexelStaging ownedSource = source;
            PackedGeometry ownedGeometry = geometry;
            source = null;
            geometry = null;
            try {
                if (ownedSource != null) {
                    ownedSource.close();
                }
            } finally {
                if (ownedGeometry != null) {
                    ownedGeometry.close();
                }
            }
        }

        private void requireOpen() {
            if (closed || source == null || geometry == null) {
                throw new IllegalStateException("The exact D1 static skinned source has already been released");
            }
        }
    }

    private static final class PackedGeometry implements AutoCloseable {
        private final int vertexCount;
        private final int indexCount;
        private final int vertexByteCount;
        private final int indexByteCount;
        private ByteBuffer vertexBytes;
        private ByteBuffer indexBytes;

        private PackedGeometry(
                int vertexCount,
                int indexCount,
                int vertexByteCount,
                int indexByteCount,
                ByteBuffer vertexBytes,
                ByteBuffer indexBytes) {
            this.vertexCount = vertexCount;
            this.indexCount = indexCount;
            this.vertexByteCount = vertexByteCount;
            this.indexByteCount = indexByteCount;
            this.vertexBytes = vertexBytes;
            this.indexBytes = indexBytes;
        }

        private static PackedGeometry fromExactPreparedGeometry(PreparedSkinnedGeometry geometry) {
            PreparedSkinnedGeometry checkedGeometry = Objects.requireNonNull(geometry, "geometry");
            SkinnedMeshTopology topology = checkedGeometry.topology();
            int vertices = checkedGeometry.vertexCount();
            int indices = topology.indexCount();
            if (vertices <= 0 || vertices > MAX_VERTICES || topology.vertexCount() != vertices
                    || indices <= 0 || indices > MAX_INDICES || indices % 3 != 0) {
                throw new IllegalArgumentException("T4p skinned geometry is outside the frozen vertex/index bounds");
            }
            int vertexBytes = checkedByteCount(vertices, VERTEX_STRIDE_BYTES, MAX_VERTEX_BYTES, "vertex");
            int indexBytes = checkedByteCount(indices, Integer.BYTES, MAX_INDEX_BYTES, "index");
            float[] positions = checkedGeometry.positions();
            float[] normals = checkedGeometry.normals();
            float[] texCoords = topology.texCoords();
            int[] triangleIndices = topology.indices();
            if (positions.length != vertices * 3 || normals.length != positions.length
                    || texCoords.length != vertices * 2 || triangleIndices.length != indices) {
                throw new IllegalArgumentException("T4p skinned geometry lost its exact attribute cardinality");
            }

            ByteBuffer packedVertices = ByteBuffer.allocateDirect(vertexBytes).order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer packedIndices = ByteBuffer.allocateDirect(indexBytes).order(ByteOrder.LITTLE_ENDIAN);
            for (int vertex = 0; vertex < vertices; vertex++) {
                int position = vertex * 3;
                int uv = vertex * 2;
                packedVertices.putFloat(finite(positions[position], "position X"));
                packedVertices.putFloat(finite(positions[position + 1], "position Y"));
                packedVertices.putFloat(finite(positions[position + 2], "position Z"));
                packedVertices.putFloat(finite(normals[position], "normal X"));
                packedVertices.putFloat(finite(normals[position + 1], "normal Y"));
                packedVertices.putFloat(finite(normals[position + 2], "normal Z"));
                packedVertices.putFloat(finite(texCoords[uv], "UV U"));
                packedVertices.putFloat(finite(texCoords[uv + 1], "UV V"));
            }
            for (int index : triangleIndices) {
                if (index < 0 || index >= vertices) {
                    throw new IllegalArgumentException("T4p skinned triangle index is outside the exact vertex range");
                }
                packedIndices.putInt(index);
            }
            packedVertices.flip();
            packedIndices.flip();
            return new PackedGeometry(
                    vertices,
                    indices,
                    vertexBytes,
                    indexBytes,
                    readOnlyLittleEndian(packedVertices),
                    readOnlyLittleEndian(packedIndices));
        }

        @Override
        public void close() {
            vertexBytes = null;
            indexBytes = null;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void rethrowUnchecked(Throwable failure) throws T {
        throw (T) failure;
    }

    /**
     * Typed, read-only exact palette upload unit. Its constructor is private and it retains the original validated
     * palette snapshot, so a T3c adapter cannot pair arbitrary direct bytes with a frame proof.
     */
    public static final class PaletteUpload implements AutoCloseable {
        private final X7SkinnedFrameProvenance frame;
        private X7GpuSkinPaletteSnapshot snapshot;
        private boolean closed;

        private PaletteUpload(X7SkinnedFrameProvenance frame, X7GpuSkinPaletteSnapshot snapshot) {
            this.frame = Objects.requireNonNull(frame, "frame");
            this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        }

        private static PaletteUpload capture(X7SkinnedFrameProvenance frame, X7GpuSkinPaletteSnapshot snapshot) {
            X7GpuSkinPaletteSnapshot checkedSnapshot = Objects.requireNonNull(snapshot, "snapshot");
            ByteBuffer bytes = checkedSnapshot.bytesForUpload();
            if (!bytes.isDirect() || bytes.remaining() != X7GpuSkinPaletteSnapshot.TOTAL_BYTES) {
                throw new IllegalStateException("Validated T4p palette lost its exact direct 8 KiB upload representation");
            }
            return new PaletteUpload(frame, checkedSnapshot);
        }

        /** Returns only the read-only exact 8 KiB bytes owned by this typed proof unit. */
        public synchronized ByteBuffer bytesForUpload() {
            if (closed || snapshot == null) {
                throw new IllegalStateException("The exact T4p palette upload has already been released");
            }
            return snapshot.bytesForUpload();
        }

        /** Package-private exact frame test used by the T4 reload/pass adapters. */
        synchronized boolean matchesExactly(X7SkinnedFrameProvenance candidate) {
            return frame == Objects.requireNonNull(candidate, "candidate");
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            X7GpuSkinPaletteSnapshot owned = snapshot;
            snapshot = null;
            if (owned != null) {
                owned.close();
            }
        }
    }

    /** CPU fallback result: no materialized bytes and therefore no native allocation path exist. */
    public static final class Attempt {
        private final X7SkinnedTexelProvenance provenance;
        private final Throwable fallbackCause;

        private Attempt(X7SkinnedTexelProvenance provenance, Throwable fallbackCause) {
            this.provenance = provenance;
            this.fallbackCause = fallbackCause;
        }

        private static Attempt accepted(X7SkinnedTexelProvenance provenance) {
            return new Attempt(Objects.requireNonNull(provenance, "provenance"), null);
        }

        private static Attempt cpuFallback(Throwable failure) {
            return new Attempt(null, Objects.requireNonNull(failure, "failure"));
        }

        public boolean eligible() {
            return provenance != null;
        }

        public X7SkinnedTexelProvenance provenanceOrNull() {
            return provenance;
        }

        public Throwable fallbackCauseOrNull() {
            return fallbackCause;
        }
    }
}
