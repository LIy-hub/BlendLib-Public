package com.liy.blendlib.fabric.client.host;

import com.liy.blendlib.api.HostRegistrationSpec;
import java.util.Objects;

/**
 * Immutable accepted X1 registration retained by the 26.1.2 platform bridge.
 *
 * <p>The opaque stable host token stays typed and uninspected. The binding names the existing
 * Fabric seam that must consume it, while X4's new targets remain separately typed factories.
 * It is not an empty acknowledgement: the adapter keeps these immutable bindings for duplicate
 * rejection, deterministic handoff ordering, and integration-owned renderer installation.</p>
 *
 * @param <H> stable consumer host-token type
 */
public final class X4StableHostBinding<H> {
    private final HostRegistrationSpec<H> specification;
    private final X4StableHostSeam seam;
    private final long revision;

    X4StableHostBinding(HostRegistrationSpec<H> specification, long revision) {
        this.specification = Objects.requireNonNull(specification, "specification");
        this.seam = X4StableHostSeam.from(specification.hostKind());
        if (revision <= 0L) {
            throw new IllegalArgumentException("X4 stable binding revision must be positive");
        }
        this.revision = revision;
    }

    /** Complete immutable X1 semantic registration for the integration-owned seam. */
    public HostRegistrationSpec<H> specification() {
        return specification;
    }

    /** Existing client seam that must install the stable registration. */
    public X4StableHostSeam seam() {
        return seam;
    }

    /** Monotonic bridge-local acceptance revision used for deterministic integration snapshots. */
    public long revision() {
        return revision;
    }
}
