package com.liy.blendlib.fabric.client.reload;

import com.liy.blendlib.fabric.client.render.x7gpu.X7SkinnedFrameProvenance;
import com.liy.blendlib.fabric.client.render.x7gpu.X7SkinnedTexelProvenance;
import com.liy.blendlib.fabric.client.render.x7gpu.X7SkinnedTexelProvenance.StaticSource;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * One caller-owned exact upload bundle for the T4 skinned D1 leaf.
 *
 * <p>The three generation GPU streams originate in one {@link StaticSource} captured from exact immutable geometry
 * and influences. The separate {@link X7SkinnedTexelProvenance} is current-frame palette/parity proof only and is
 * never retained by D1. This seam has no caller-authored raw bytes or identity argument.</p>
 */
final class SkinnedTexelUploadStaging implements AutoCloseable {
    private StaticSource staticSource;
    private boolean transferredToLeaf;
    private boolean closed;

    private SkinnedTexelUploadStaging(StaticSource staticSource) {
        this.staticSource = Objects.requireNonNull(staticSource, "staticSource");
    }

    /**
     * Captures a D1-owned static source only when the current proof belongs to the exact frame. The source constructor
     * reads only immutable geometry/influences; it cannot retain or derive its bytes from this transient palette.
     */
    static SkinnedTexelUploadStaging capture(
            X7SkinnedFrameProvenance frame, X7SkinnedTexelProvenance provenance) {
        X7SkinnedTexelProvenance checkedProvenance = Objects.requireNonNull(provenance, "provenance");
        StaticSource source = null;
        try {
            X7SkinnedFrameProvenance checkedFrame = Objects.requireNonNull(frame, "frame");
            if (!checkedProvenance.matchesExactly(checkedFrame)) {
                throw new IllegalArgumentException("T4p materialized bytes do not belong to this exact extraction frame");
            }
            source = StaticSource.captureForGeneration(checkedFrame);
            if (!source.matchesExactly(checkedProvenance)) {
                throw new IllegalArgumentException("The D1 static source lost its exact current-frame geometry identity");
            }
            return new SkinnedTexelUploadStaging(source);
        } catch (Throwable failure) {
            if (source != null) {
                source.close();
            }
            CompletedGenerationResourceSet.throwUnchecked(failure);
            throw new AssertionError("unreachable");
        }
    }

    synchronized StaticSource staticSource() {
        requireOpen();
        return staticSource;
    }

    synchronized int vertexCount() {
        requireOpen();
        return staticSource.vertexCount();
    }

    synchronized int indexCount() {
        requireOpen();
        return staticSource.indexCount();
    }

    synchronized int vertexByteCount() {
        requireOpen();
        return staticSource.vertexByteCount();
    }

    synchronized int indexByteCount() {
        requireOpen();
        return staticSource.indexByteCount();
    }

    synchronized int sourceByteCount() {
        requireOpen();
        return staticSource.sourceByteCount();
    }

    synchronized ByteBuffer vertexBytesForUpload() {
        requireOpen();
        return staticSource.vertexBytesForD1Upload();
    }

    synchronized ByteBuffer indexBytesForUpload() {
        requireOpen();
        return staticSource.indexBytesForD1Upload();
    }

    synchronized ByteBuffer sourceBytesForUpload() {
        requireOpen();
        return staticSource.sourceBytesForD1Upload();
    }

    /** Transfers the sole direct-buffer owner to the completed D1 leaf after all native allocations succeed. */
    synchronized void transferStaticSourceToLeaf(SkinnedTexelGenerationResources leaf) {
        requireOpen();
        if (transferredToLeaf) {
            throw new IllegalStateException("The T4p static source was transferred to a D1 leaf more than once");
        }
        if (!Objects.requireNonNull(leaf, "leaf").matchesExactly(staticSource)) {
            throw new IllegalArgumentException("The completed D1 leaf must retain this exact T4p static source");
        }
        transferredToLeaf = true;
    }

    synchronized boolean isClosed() {
        return closed;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        StaticSource owned = staticSource;
        staticSource = null;
        if (!transferredToLeaf && owned != null) {
            owned.close();
        }
    }

    private void requireOpen() {
        if (closed || staticSource == null) {
            throw new IllegalStateException("Skinned texel upload staging has already been released");
        }
    }
}
