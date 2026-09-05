package com.liy.blendlib.examples.providers.backend;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.spi.experimental.CapabilityFallback;
import com.liy.blendlib.spi.experimental.CapabilityOffer;
import com.liy.blendlib.spi.experimental.CapabilityRequest;
import com.liy.blendlib.spi.experimental.CapabilityVersion;
import com.liy.blendlib.spi.experimental.CapabilityVersionRange;
import com.liy.blendlib.spi.experimental.ExperimentalBlendLibSpi;
import com.liy.blendlib.spi.experimental.ProviderLease;
import com.liy.blendlib.spi.experimental.ProviderLifecycleContext;
import com.liy.blendlib.spi.experimental.ProviderLifecycleStage;
import com.liy.blendlib.spi.experimental.RenderBackendProvider;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Metadata-only backend offer with an explicit CPU standard-path fallback.
 *
 * <p>No graphics API, backend object, resource lookup, or submit work is present in this package.
 * A real platform controls capability selection and backend lifecycle off the submit hot path.</p>
 */
@ExperimentalBlendLibSpi
public final class ExampleRenderBackendProvider implements RenderBackendProvider {
    public static final BlendResourceId PROVIDER_ID = BlendResourceId.parse("third_party_example:render_backend");
    public static final BlendResourceId PUBLIC_STANDARD = BlendResourceId.parse("third_party_example:public_standard_backend");
    public static final BlendResourceId CPU_STANDARD = BlendResourceId.parse("third_party_example:cpu_standard_fallback");
    private final CopyOnWriteArrayList<LifecycleRecord> lifecycle = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    @Override
    public BlendResourceId providerId() {
        return PROVIDER_ID;
    }

    @Override
    public Collection<CapabilityOffer> offers() {
        return List.of(new CapabilityOffer(PROVIDER_ID, PUBLIC_STANDARD, CapabilityVersion.CURRENT_PROTOCOL, 20));
    }

    @Override
    public Set<BlendResourceId> supportedRenderBackends() {
        return Set.of(PUBLIC_STANDARD);
    }

    public CapabilityVersionRange supportedProtocolRange() {
        return CapabilityVersion.CURRENT_PROTOCOL_RANGE;
    }

    /** Declares a bounded CPU fallback which still uses the platform's standard public route. */
    public CapabilityRequest optionalBackendRequest() {
        return CapabilityRequest.optional(
                PUBLIC_STANDARD,
                supportedProtocolRange(),
                new CapabilityFallback(CPU_STANDARD, "CPU path retains the standard public material route without shader or GL substitution."));
    }

    @Override
    public void prepare(ProviderLifecycleContext context) {
        record(context);
    }

    @Override
    public void apply(ProviderLifecycleContext context) {
        record(context);
    }

    @Override
    public void retire(ProviderLifecycleContext context) {
        record(context);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            lifecycle.add(new LifecycleRecord(-1L, ProviderLifecycleStage.CLOSE));
        }
    }

    public List<LifecycleRecord> lifecycle() {
        return List.copyOf(lifecycle);
    }

    /** A snapshot owner releases the exact session lease after it finishes using the snapshot. */
    public static void releaseSnapshotLease(ProviderLease lease) {
        Objects.requireNonNull(lease, "lease").close();
    }

    private void record(ProviderLifecycleContext context) {
        ProviderLifecycleContext checked = Objects.requireNonNull(context, "context");
        lifecycle.add(new LifecycleRecord(checked.generation(), checked.stage()));
    }

    /** Immutable record of lifecycle work performed outside backend submission. */
    public record LifecycleRecord(long generation, ProviderLifecycleStage stage) {
    }
}
