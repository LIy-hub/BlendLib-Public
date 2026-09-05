package com.liy.blendlib.fabric.client.host;

import com.liy.blendlib.api.BlendModelKey;
import java.util.Objects;

/** Immutable, target-specific host specification created by an X4 builder. */
public record X4HostSpec<F extends X4HostFrame>(
        X4HostKind hostKind,
        BlendModelKey modelKey,
        X4HostIdentity identity,
        X4HostConfiguration<F> configuration) {
    public X4HostSpec {
        hostKind = Objects.requireNonNull(hostKind, "hostKind");
        modelKey = Objects.requireNonNull(modelKey, "modelKey");
        identity = Objects.requireNonNull(identity, "identity");
        configuration = Objects.requireNonNull(configuration, "configuration");
        if (configuration.hostKind() != hostKind) {
            throw new IllegalArgumentException("X4 host configuration kind must match the built host kind");
        }
        configuration.validateSpecificationIdentity(identity);
    }
}
