package com.liy.blendlib.examples.providers.adapter;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.api.HostRegistrationSpec;
import com.liy.blendlib.api.RegistrationReceipt;
import com.liy.blendlib.spi.experimental.CapabilityOffer;
import com.liy.blendlib.spi.experimental.CapabilityRequest;
import com.liy.blendlib.spi.experimental.CapabilityVersion;
import com.liy.blendlib.spi.experimental.CapabilityVersionRange;
import com.liy.blendlib.spi.experimental.ExperimentalBlendLibSpi;
import com.liy.blendlib.spi.experimental.PlatformAdapter;
import com.liy.blendlib.spi.experimental.ProviderLease;
import com.liy.blendlib.spi.experimental.ProviderLifecycleContext;
import com.liy.blendlib.spi.experimental.ProviderLifecycleStage;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Controlled Experimental platform-adapter shape for a version-specific lifecycle owner.
 *
 * <p>This class intentionally does not call PlatformAdapterControl. A real platform bootstrap
 * owns installation, one active adapter, registration timing, lease/session construction, reload,
 * retirement, and uninstallation. The adapter returns only stable receipts and records no host
 * object, renderer, resource, GL state, or reflection handle.</p>
 */
@ExperimentalBlendLibSpi
public final class ExamplePlatformAdapter implements PlatformAdapter {
    public static final BlendResourceId PROVIDER_ID = BlendResourceId.parse("third_party_example:platform_adapter");
    public static final BlendResourceId HOST_ADAPTER_CAPABILITY =
            BlendResourceId.parse("third_party_example:semantic_host_adapter");
    private final CopyOnWriteArrayList<RegistrationRecord> registrations = new CopyOnWriteArrayList<>();
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
                HOST_ADAPTER_CAPABILITY,
                CapabilityVersion.CURRENT_PROTOCOL,
                50));
    }

    /** Returns the exact X1/X4 opt-in line this adapter expects. */
    public CapabilityVersionRange supportedProtocolRange() {
        return CapabilityVersion.CURRENT_PROTOCOL_RANGE;
    }

    /** Platform bootstrap code can require this capability before it attempts controlled install. */
    public CapabilityRequest requiredHostAdapterRequest() {
        return CapabilityRequest.required(HOST_ADAPTER_CAPABILITY, supportedProtocolRange());
    }

    @Override
    public <H> RegistrationReceipt register(HostRegistrationSpec<H> specification) {
        HostRegistrationSpec<H> checked = Objects.requireNonNull(specification, "specification");
        if (closed.get()) {
            throw new IllegalStateException("Closed platform adapter cannot accept a registration.");
        }
        RegistrationReceipt receipt = new RegistrationReceipt(PROVIDER_ID, checked.hostKind(), checked.model());
        registrations.add(new RegistrationRecord(checked.hostKind().name(), checked.model().value()));
        return receipt;
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
            registrations.clear();
            lifecycle.add(new LifecycleRecord(-1L, ProviderLifecycleStage.CLOSE));
        }
    }

    /** Immutable registration diagnostics with no host object or platform-private adapter state. */
    public List<RegistrationRecord> registrations() {
        return List.copyOf(registrations);
    }

    /** Immutable lifecycle diagnostics for the external lifecycle owner. */
    public List<LifecycleRecord> lifecycle() {
        return List.copyOf(lifecycle);
    }

    /** A snapshot/session owner releases only the exact lease it previously received. */
    public static void releaseSnapshotLease(ProviderLease lease) {
        Objects.requireNonNull(lease, "lease").close();
    }

    private void record(ProviderLifecycleContext context) {
        ProviderLifecycleContext checked = Objects.requireNonNull(context, "context");
        lifecycle.add(new LifecycleRecord(checked.generation(), checked.stage()));
    }

    /** Stable-only registration observation. */
    public record RegistrationRecord(String hostKind, String modelKey) {
    }

    /** Non-hot lifecycle observation. */
    public record LifecycleRecord(long generation, ProviderLifecycleStage stage) {
    }
}
