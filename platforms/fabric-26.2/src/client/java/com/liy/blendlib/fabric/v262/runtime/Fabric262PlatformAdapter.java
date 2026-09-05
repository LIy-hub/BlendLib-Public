package com.liy.blendlib.fabric.v262.runtime;

import com.liy.blendlib.api.BlendApiDiagnostic;
import com.liy.blendlib.api.BlendApiDiagnosticCode;
import com.liy.blendlib.api.BlendDiagnosticSeverity;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.api.BlendRegistrationException;
import com.liy.blendlib.api.HostRegistrationSpec;
import com.liy.blendlib.api.RegistrationReceipt;
import com.liy.blendlib.fabric.v262.host.Fabric262HostBinding;
import com.liy.blendlib.fabric.v262.host.Fabric262HostBindingRegistry;
import com.liy.blendlib.fabric.v262.host.Fabric262HostRenderDispatcher;
import com.liy.blendlib.fabric.v262.host.Fabric262HostRegistrationTranslator;
import com.liy.blendlib.fabric.v262.resource.Fabric262ResourceReloadCoordinator;
import com.liy.blendlib.spi.experimental.CapabilityOffer;
import com.liy.blendlib.spi.experimental.CapabilityVersion;
import com.liy.blendlib.spi.experimental.ExperimentalBlendLibSpi;
import com.liy.blendlib.spi.experimental.PlatformAdapter;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Controlled experimental platform adapter for the standalone Minecraft 26.2 Fabric artifact.
 *
 * <p><strong>Experimental SPI / platform target:</strong> Fabric on Minecraft 26.2. It translates
 * the stable pure semantic facade into public native entity, block-entity, and item dispatchers
 * without exposing any Fabric type to API/core. It advertises only capabilities with an installed
 * standard CPU/collector path; required unadvertised capabilities remain fail-closed through the
 * frozen X1 protocol.</p>
 */
@ExperimentalBlendLibSpi
public final class Fabric262PlatformAdapter implements PlatformAdapter {
    /** Canonical independent provider identity for this exact Fabric/Minecraft target. */
    public static final BlendResourceId ADAPTER_ID = BlendResourceId.of("blendlib", "fabric_26_2");

    private static final Collection<CapabilityOffer> OFFERS = List.of(
            new CapabilityOffer(
                    ADAPTER_ID,
                    BlendResourceId.of("blendlib", "host_registration"),
                    CapabilityVersion.CURRENT_PROTOCOL,
                    0),
            new CapabilityOffer(
                    ADAPTER_ID,
                    BlendResourceId.of("blendlib", "strict_glb_rigid_v1"),
                    CapabilityVersion.CURRENT_PROTOCOL,
                    0));

    private final Fabric262HostRegistrationTranslator translator;
    private final Fabric262HostBindingRegistry bindings;
    private final Fabric262HostRenderDispatcher dispatcher;
    private final Object lifecycleMonitor = new Object();
    private boolean closing;
    private boolean closed;

    /**
     * Creates an adapter bound to one active Fabric 26.2 resource coordinator.
     *
     * @param coordinator runtime-owned strict resource lifecycle coordinator
     */
    public Fabric262PlatformAdapter(Fabric262ResourceReloadCoordinator coordinator) {
        this(
                Objects.requireNonNull(coordinator, "coordinator"),
                new Fabric262HostRegistrationTranslator(),
                new Fabric262HostBindingRegistry());
    }

    /**
     * Creates an adapter from explicit platform-owned translation collaborators.
     *
     * @param coordinator runtime-owned strict resource lifecycle coordinator
     * @param translator native Fabric host translator
     * @param bindings adapter-owned translated binding registry
     */
    public Fabric262PlatformAdapter(
            Fabric262ResourceReloadCoordinator coordinator,
            Fabric262HostRegistrationTranslator translator,
            Fabric262HostBindingRegistry bindings) {
        this.translator = Objects.requireNonNull(translator, "translator");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.dispatcher = new Fabric262HostRenderDispatcher(
                Objects.requireNonNull(coordinator, "coordinator"), this.translator, this.bindings);
    }

    /**
     * Returns the exact version-specific adapter identity.
     *
     * @return canonical Fabric 26.2 provider identity
     */
    @Override
    public BlendResourceId providerId() {
        return ADAPTER_ID;
    }

    /**
     * Returns immutable capability metadata only; discovery is never performed during submit.
     *
     * @return immutable implemented capability offers
     */
    @Override
    public Collection<CapabilityOffer> offers() {
        return OFFERS;
    }

    /**
     * Translates and installs one semantic ordinary-host registration exactly once.
     *
     * @param specification completed pure stable registration specification
     * @param <H> opaque consumer host type, validated only at the Fabric boundary
     * @return receipt consistent with the controlled stable facade contract
     * @throws BlendRegistrationException when this adapter is closed or native translation fails
     */
    @Override
    public <H> RegistrationReceipt register(HostRegistrationSpec<H> specification) {
        synchronized (lifecycleMonitor) {
            if (closing) {
                throw registrationFailure("Fabric 26.2 platform adapter is closing or closed");
            }
        }
        HostRegistrationSpec<H> checked = Objects.requireNonNull(specification, "specification");
        try {
            dispatcher.register(checked);
            return new RegistrationReceipt(ADAPTER_ID, checked.hostKind(), checked.model());
        } catch (RuntimeException exception) {
            if (exception instanceof BlendRegistrationException registrationException) {
                throw registrationException;
            }
            throw registrationFailure("Fabric 26.2 host translation failed: " + exception.getMessage(), exception);
        }
    }

    /**
     * Returns deterministic dispatcher-installed binding metadata without exposing native renderer objects.
     *
     * @return immutable translated host binding snapshot
     */
    public List<Fabric262HostBinding> hostBindings() {
        return bindings.bindings();
    }

    /**
     * Drains dispatcher snapshots and closes adapter-owned binding state after exact control release.
     *
     * <p>This method runs only from the experimental ownership protocol; it neither triggers
     * resource reloads nor submits geometry nor changes authoritative game state.</p>
     */
    @Override
    public void close() {
        synchronized (lifecycleMonitor) {
            if (closed) {
                return;
            }
            closing = true;
            dispatcher.close();
            closed = true;
        }
    }

    private static BlendRegistrationException registrationFailure(String message) {
        return registrationFailure(message, null);
    }

    private static BlendRegistrationException registrationFailure(String message, Throwable cause) {
        BlendApiDiagnostic diagnostic = new BlendApiDiagnostic(
                BlendApiDiagnosticCode.PLATFORM_ADAPTER_FAILURE,
                BlendDiagnosticSeverity.ERROR,
                bounded(message));
        return cause == null
                ? new BlendRegistrationException(diagnostic)
                : new BlendRegistrationException(diagnostic, cause);
    }

    private static String bounded(String message) {
        String checked = Objects.requireNonNull(message, "message");
        return checked.length() <= BlendApiDiagnostic.MAX_MESSAGE_LENGTH
                ? checked
                : checked.substring(0, BlendApiDiagnostic.MAX_MESSAGE_LENGTH - 3) + "...";
    }
}
