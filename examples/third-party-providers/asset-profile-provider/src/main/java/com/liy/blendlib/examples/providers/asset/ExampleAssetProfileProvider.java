package com.liy.blendlib.examples.providers.asset;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.spi.experimental.AssetProfileProvider;
import com.liy.blendlib.spi.experimental.CapabilityOffer;
import com.liy.blendlib.spi.experimental.CapabilityRequest;
import com.liy.blendlib.spi.experimental.CapabilityVersion;
import com.liy.blendlib.spi.experimental.CapabilityVersionRange;
import com.liy.blendlib.spi.experimental.ExperimentalBlendLibSpi;
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
 * Standalone metadata provider for the strict rigid-v1 profile.
 *
 * <p>The lifecycle list is a diagnostic snapshot for a host to inspect; it is never consulted by
 * rendering or a submit path.</p>
 */
@ExperimentalBlendLibSpi
public final class ExampleAssetProfileProvider implements AssetProfileProvider {
    public static final BlendResourceId PROVIDER_ID = BlendResourceId.parse("third_party_example:asset_profiles");
    public static final BlendResourceId RIGID_PROFILE = BlendResourceId.parse("blendlib:rigid_v1");
    private final CopyOnWriteArrayList<LifecycleRecord> lifecycle = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    @Override
    public BlendResourceId providerId() {
        return PROVIDER_ID;
    }

    @Override
    public Collection<CapabilityOffer> offers() {
        return List.of(new CapabilityOffer(PROVIDER_ID, RIGID_PROFILE, CapabilityVersion.CURRENT_PROTOCOL, 40));
    }

    @Override
    public Set<BlendResourceId> supportedAssetProfiles() {
        return Set.of(RIGID_PROFILE);
    }

    /** Returns the exact current opt-in range rather than a broad historical protocol range. */
    public CapabilityVersionRange supportedProtocolRange() {
        return CapabilityVersion.CURRENT_PROTOCOL_RANGE;
    }

    /** Creates a required profile request with no fallback because profile mismatch must fail closed. */
    public CapabilityRequest requiredRigidProfile() {
        return CapabilityRequest.required(RIGID_PROFILE, supportedProtocolRange());
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

    /** Returns an immutable lifecycle observation that leaks no provider handle. */
    public List<LifecycleRecord> lifecycle() {
        return List.copyOf(lifecycle);
    }

    /** Releases a lease supplied by the owning published-generation/session code after snapshot use. */
    public static void releaseSnapshotLease(ProviderLease lease) {
        Objects.requireNonNull(lease, "lease").close();
    }

    private void record(ProviderLifecycleContext context) {
        ProviderLifecycleContext checked = Objects.requireNonNull(context, "context");
        lifecycle.add(new LifecycleRecord(checked.generation(), checked.stage()));
    }

    /** Immutable lifecycle observation with no renderer, resource, or asset payload. */
    public record LifecycleRecord(long generation, ProviderLifecycleStage stage) {
    }
}
