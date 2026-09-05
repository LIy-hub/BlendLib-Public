package com.liy.blendlib.examples.providers.host;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.api.HostKind;
import com.liy.blendlib.spi.experimental.CapabilityOffer;
import com.liy.blendlib.spi.experimental.CapabilityVersion;
import com.liy.blendlib.spi.experimental.CapabilityVersionRange;
import com.liy.blendlib.spi.experimental.ExperimentalBlendLibSpi;
import com.liy.blendlib.spi.experimental.HostRendererProvider;
import com.liy.blendlib.spi.experimental.ProviderLease;
import com.liy.blendlib.spi.experimental.ProviderLifecycleContext;
import com.liy.blendlib.spi.experimental.ProviderLifecycleStage;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Host-kind compatibility provider that does not leak a platform renderer object.
 *
 * <p>A platform integration uses the declared HostKind set during controlled preparation, never
 * as a request to reflect over a host or acquire a renderer during submit.</p>
 */
@ExperimentalBlendLibSpi
public final class ExampleHostRendererProvider implements HostRendererProvider {
    public static final BlendResourceId PROVIDER_ID = BlendResourceId.parse("third_party_example:host_renderers");
    public static final BlendResourceId HOST_RENDERER_CAPABILITY =
            BlendResourceId.parse("third_party_example:semantic_host_renderer");
    private final CopyOnWriteArrayList<LifecycleRecord> lifecycle = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    @Override
    public BlendResourceId providerId() {
        return PROVIDER_ID;
    }

    @Override
    public Collection<CapabilityOffer> offers() {
        return List.of(new CapabilityOffer(
                PROVIDER_ID,
                HOST_RENDERER_CAPABILITY,
                CapabilityVersion.CURRENT_PROTOCOL,
                10));
    }

    @Override
    public Set<HostKind> supportedHostKinds() {
        return Set.of(HostKind.ENTITY, HostKind.BLOCK_ENTITY, HostKind.ITEM);
    }

    public CapabilityVersionRange supportedProtocolRange() {
        return CapabilityVersion.CURRENT_PROTOCOL_RANGE;
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

    /** The session/snapshot owner, rather than this metadata provider, closes its obtained lease. */
    public static void releaseSnapshotLease(ProviderLease lease) {
        Objects.requireNonNull(lease, "lease").close();
    }

    private void record(ProviderLifecycleContext context) {
        ProviderLifecycleContext checked = Objects.requireNonNull(context, "context");
        lifecycle.add(new LifecycleRecord(checked.generation(), checked.stage()));
    }

    /** Immutable non-renderer lifecycle observation. */
    public record LifecycleRecord(long generation, ProviderLifecycleStage stage) {
    }
}
