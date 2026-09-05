package com.liy.blendlib.fabric.v262.host;

import com.liy.blendlib.fabric.v262.model.Fabric262RenderSnapshot;
import java.util.Objects;

/**
 * X4-capable carrier pairing an ordinary Fabric host binding with one prepared render snapshot.
 *
 * <p>This optional carrier preserves immutable metadata plus exact snapshot ownership for an
 * integration that needs to hand a prepared value between two client-only calls. The production
 * Fabric 26.2 entity, block-entity, and item dispatcher uses its own direct public callbacks and
 * does not depend on X4's 26.1.2 renderer types.</p>
 */
public final class Fabric262HostRenderCarrier implements AutoCloseable {
    private final Fabric262HostBinding binding;
    private final Fabric262RenderSnapshot snapshot;
    private boolean closed;

    /**
     * Creates a carrier that owns one open prepared render snapshot.
     *
     * @param binding immutable ordinary-host binding
     * @param snapshot open generation-pinned snapshot
     */
    public Fabric262HostRenderCarrier(Fabric262HostBinding binding, Fabric262RenderSnapshot snapshot) {
        this.binding = Objects.requireNonNull(binding, "binding");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }

    /**
     * Returns the immutable semantic/native host binding.
     *
     * @return non-null host binding
     */
    public Fabric262HostBinding binding() {
        return binding;
    }

    /**
     * Returns the open prepared render snapshot for the final renderer seam.
     *
     * @return open generation-pinned snapshot
     */
    public synchronized Fabric262RenderSnapshot snapshot() {
        if (closed) {
            throw new IllegalStateException("A closed Fabric 26.2 host render carrier has no usable snapshot");
        }
        return snapshot;
    }

    /**
     * Closes exactly the carried snapshot; repeated close calls are harmless.
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        snapshot.close();
        closed = true;
    }
}
