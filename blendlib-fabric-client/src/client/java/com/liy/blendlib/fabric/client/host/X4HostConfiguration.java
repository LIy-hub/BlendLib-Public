package com.liy.blendlib.fabric.client.host;

import com.liy.blendlib.fabric.client.render.ModelRenderHandle;
import java.util.Objects;

/**
 * Immutable target-specific configuration validated during X4 extraction.
 *
 * <p>Implementations validate already supplied frame/handle data only. They must not perform
 * resource I/O, GLB/JSON parsing, provider discovery, raw graphics access, reflection, or
 * submit-time socket lookup; those activities belong to separately controlled preparation and
 * platform lifecycle ownership.</p>
 */
public interface X4HostConfiguration<F extends X4HostFrame> {
    /** Target category implemented by this immutable configuration. */
    X4HostKind hostKind();

    /** Largest allowed model-bounds extent for a non-missing prepared model. */
    float maximumBoundsExtent();

    /** Validates that immutable configuration graph roots/scopes belong to this exact host spec. */
    default void validateSpecificationIdentity(X4HostIdentity identity) {
        Objects.requireNonNull(identity, "identity");
    }

    /** Validates host-specific frame state before lookup or captured-snapshot use. */
    void validateFrame(X4HostSpec<F> specification, F frame);

    /** Validates one already prepared handle without performing any model lookup or I/O. */
    default void validatePreparedHandle(ModelRenderHandle handle) {
        if (handle.missingModel()) {
            return;
        }
        float extent = Math.max(
                handle.bounds().max().x() - handle.bounds().min().x(),
                Math.max(
                        handle.bounds().max().y() - handle.bounds().min().y(),
                        handle.bounds().max().z() - handle.bounds().min().z()));
        if (!Float.isFinite(extent) || extent < 0.0F || extent > maximumBoundsExtent()) {
            throw new IllegalArgumentException("Prepared model bounds exceed the configured X4 host limit");
        }
    }
}
