package com.liy.blendlib.examples.providers.material;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.spi.experimental.CapabilityFallback;
import com.liy.blendlib.spi.experimental.CapabilityOffer;
import com.liy.blendlib.spi.experimental.CapabilityRequest;
import com.liy.blendlib.spi.experimental.CapabilityVersion;
import com.liy.blendlib.spi.experimental.CapabilityVersionRange;
import com.liy.blendlib.spi.experimental.ExperimentalBlendLibSpi;
import com.liy.blendlib.spi.experimental.MaterialProvider;
import com.liy.blendlib.spi.experimental.ProviderLease;
import com.liy.blendlib.spi.experimental.ProviderLifecycleContext;
import com.liy.blendlib.spi.experimental.ProviderLifecycleStage;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** Metadata-only provider for one standard opaque material route. */
@ExperimentalBlendLibSpi
public final class ExampleMaterialProvider implements MaterialProvider {
    public static final BlendResourceId PROVIDER_ID = BlendResourceId.parse("third_party_example:materials");
    public static final BlendResourceId OPAQUE_STANDARD = BlendResourceId.parse("third_party_example:opaque_standard");
    public static final BlendResourceId OPAQUE_FALLBACK = BlendResourceId.parse("third_party_example:opaque_cpu_fallback");
    private final CopyOnWriteArrayList<LifecycleRecord> lifecycle = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    @Override
    public BlendResourceId providerId() {
        return PROVIDER_ID;
    }

    @Override
    public Collection<CapabilityOffer> offers() {
        return List.of(new CapabilityOffer(PROVIDER_ID, OPAQUE_STANDARD, CapabilityVersion.CURRENT_PROTOCOL, 30));
    }

    @Override
    public Set<BlendResourceId> supportedMaterialCapabilities() {
        return Set.of(OPAQUE_STANDARD);
    }

    /** Exact current protocol range accepted by this explicitly Experimental provider. */
    public CapabilityVersionRange supportedProtocolRange() {
        return CapabilityVersion.CURRENT_PROTOCOL_RANGE;
    }

    /**
     * Declares a semantic-equivalent standard-route fallback. The selecting host must validate it
     * before publishing; this declaration does not authorize a custom pipeline.
     */
    public CapabilityRequest optionalOpaqueRequest() {
        return CapabilityRequest.optional(
                OPAQUE_STANDARD,
                supportedProtocolRange(),
                new CapabilityFallback(OPAQUE_FALLBACK, "CPU standard opaque route preserves the declared material semantics."));
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

    /** Session/snapshot owner releases the exact lease only after its own snapshot work completes. */
    public static void releaseSnapshotLease(ProviderLease lease) {
        Objects.requireNonNull(lease, "lease").close();
    }

    private void record(ProviderLifecycleContext context) {
        ProviderLifecycleContext checked = Objects.requireNonNull(context, "context");
        lifecycle.add(new LifecycleRecord(checked.generation(), checked.stage()));
    }

    /** Immutable lifecycle observation, intentionally free of material implementation handles. */
    public record LifecycleRecord(long generation, ProviderLifecycleStage stage) {
    }
}
