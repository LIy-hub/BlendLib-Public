package com.liy.blendlib.fabric.v262.model;

import com.liy.blendlib.api.BlendModelKey;
import java.util.Objects;

/**
 * Immutable generation-pinned submission input for Minecraft 26.2 Fabric.
 *
 * <p><strong>Platform target:</strong> Fabric on Minecraft 26.2. A snapshot prevents a reload
 * from releasing its prepared generation while a caller still owns this input. It contains only a
 * prepared handle and copied frame state; closing it performs no render or resource I/O.</p>
 */
public final class Fabric262RenderSnapshot implements AutoCloseable {
    private final Fabric262ResourceGeneration generation;
    private final Fabric262PreparedModelHandle handle;
    private final Fabric262FrameState frame;
    private boolean closed;

    Fabric262RenderSnapshot(
            Fabric262ResourceGeneration generation,
            Fabric262PreparedModelHandle handle,
            Fabric262FrameState frame) {
        this.generation = Objects.requireNonNull(generation, "generation");
        this.handle = Objects.requireNonNull(handle, "handle");
        this.frame = Objects.requireNonNull(frame, "frame");
    }

    /**
     * Returns the semantic key of the prepared model selected for this snapshot.
     *
     * @return strict semantic model key
     */
    public BlendModelKey modelKey() {
        return handle.modelKey();
    }

    /**
     * Returns the immutable resource generation retained by this snapshot.
     *
     * @return non-negative generation number
     */
    public long generation() {
        return handle.generation();
    }

    /**
     * Returns the copied frame state used by the standard submitter.
     *
     * @return immutable per-submit state
     */
    public Fabric262FrameState frame() {
        return frame;
    }

    /**
     * Reports whether this snapshot is already released.
     *
     * @return true after the first successful close
     */
    public synchronized boolean isClosed() {
        return closed;
    }

    /**
     * Releases exactly this generation pin; repeated closes are harmless.
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        // Do not publish terminal state before the generation has accepted the exact release.
        // If that operation throws, this snapshot remains open and the dispatcher can retry it.
        generation.releaseSnapshot();
        closed = true;
    }

    /**
     * Returns the immutable prepared handle while this generation pin remains open.
     *
     * <p>This extraction-only platform seam exposes no resource access: the handle was already
     * copied during reload preparation and is used only to freeze an immutable pose snapshot.</p>
     */
    public synchronized Fabric262PreparedModelHandle preparedHandle() {
        if (closed) {
            throw new IllegalStateException("A closed Fabric 26.2 render snapshot cannot be submitted");
        }
        return handle;
    }

    /**
     * Package-compatible alias retained for existing 26.2 model-package callers.
     *
     * <p>New extraction code uses {@link #preparedHandle()} to make the frozen-pose boundary
     * explicit; both methods reject a released generation pin.</p>
     */
    Fabric262PreparedModelHandle handle() {
        return preparedHandle();
    }
}
