package com.liy.blendlib.fabric.client.host;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.fabric.client.api.BlendRenderer;
import com.liy.blendlib.fabric.client.api.ClientModelLookup;
import com.liy.blendlib.spi.experimental.ProviderLifecycleSession;
import java.util.Objects;

/** Mutable only while configuring one typed X4 adapter; {@link #build()} permanently seals it. */
public final class X4HostAdapterBuilder<C extends X4HostConfiguration<F>, F extends X4HostFrame> {
    private final X4HostKind hostKind;
    private final ClientModelLookup models;
    private final BlendRenderer renderer;
    private final ProviderLifecycleSession generationSession;
    private BlendModelKey modelKey;
    private X4HostIdentity identity;
    private C configuration;
    private boolean built;

    X4HostAdapterBuilder(
            X4HostKind hostKind,
            ClientModelLookup models,
            BlendRenderer renderer,
            ProviderLifecycleSession generationSession) {
        this.hostKind = Objects.requireNonNull(hostKind, "hostKind");
        this.models = Objects.requireNonNull(models, "models");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.generationSession = Objects.requireNonNull(generationSession, "generationSession");
    }

    /** Sets the required semantic model identity; this operation performs no lookup or I/O. */
    public X4HostAdapterBuilder<C, F> model(BlendModelKey modelKey) {
        ensureMutable();
        this.modelKey = Objects.requireNonNull(modelKey, "modelKey");
        return this;
    }

    /** Sets the required scoped host identity. */
    public X4HostAdapterBuilder<C, F> identity(X4HostIdentity identity) {
        ensureMutable();
        this.identity = Objects.requireNonNull(identity, "identity");
        return this;
    }

    /** Sets the immutable target-specific configuration exactly once. */
    public X4HostAdapterBuilder<C, F> configuration(C configuration) {
        ensureMutable();
        if (this.configuration != null) {
            throw new IllegalStateException("An X4 host builder accepts exactly one immutable configuration");
        }
        C checked = Objects.requireNonNull(configuration, "configuration");
        if (checked.hostKind() != hostKind) {
            throw new IllegalArgumentException("The supplied X4 configuration belongs to another host kind");
        }
        this.configuration = checked;
        return this;
    }

    /** Builds a real lifecycle adapter with immutable configuration. */
    public X4HostAdapter<F> build() {
        ensureMutable();
        if (modelKey == null) {
            throw new IllegalStateException("An X4 host adapter requires a model key");
        }
        if (identity == null) {
            throw new IllegalStateException("An X4 host adapter requires a scoped identity");
        }
        if (configuration == null) {
            throw new IllegalStateException("An X4 host adapter requires target-specific configuration");
        }
        X4HostSpec<F> specification = new X4HostSpec<>(hostKind, modelKey, identity, configuration);
        // Failed validation must not silently consume a builder: a caller can still complete its
        // immutable configuration and retry. A successful build is the one-way seal point.
        built = true;
        return new DefaultX4HostAdapter<>(specification, models, renderer, generationSession);
    }

    private void ensureMutable() {
        if (built) {
            throw new IllegalStateException("This X4 host builder was already built and cannot be mutated");
        }
    }
}
