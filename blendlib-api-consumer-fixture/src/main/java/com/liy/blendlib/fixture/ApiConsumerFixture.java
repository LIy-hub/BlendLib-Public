package com.liy.blendlib.fixture;

import com.liy.blendlib.api.AnimationRequest;
import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendApiDiagnosticCode;
import com.liy.blendlib.api.BlendLib;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendRegistrationException;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.api.ModelInstance;
import com.liy.blendlib.api.RegistrationReceipt;
import com.liy.blendlib.api.SocketQuery;
import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.spi.experimental.CapabilityFallback;
import com.liy.blendlib.spi.experimental.CapabilityErrorCode;
import com.liy.blendlib.spi.experimental.CapabilityNegotiationException;
import com.liy.blendlib.spi.experimental.CapabilityPlan;
import com.liy.blendlib.spi.experimental.CapabilityRegistry;
import com.liy.blendlib.spi.experimental.CapabilityRequest;
import com.liy.blendlib.spi.experimental.CapabilityVersion;
import com.liy.blendlib.spi.experimental.CapabilityVersionRange;
import com.liy.blendlib.spi.experimental.PlatformAdapter;
import com.liy.blendlib.spi.experimental.PlatformAdapterControl;
import java.time.Duration;
import java.util.Objects;

/**
 * Compile/runtime fixture showing that a consumer needs only the published API and deliberate SPI opt-in.
 *
 * <p>The ordinary entity, block-entity, and item registrations use only stable facade types. The small
 * capability section is intentionally separate and imports the explicitly experimental SPI so provider
 * authors can see the opt-in boundary without touching a platform implementation module.</p>
 */
public final class ApiConsumerFixture {
    /** Example semantic model identity used by all three stable host kinds. */
    public static final BlendModelKey MODEL = BlendModelKey.parse("consumer:examples/clockwork");

    /** Example looping animation identity. */
    public static final BlendAnimationKey IDLE = BlendAnimationKey.parse("consumer:idle");

    /** Example one-shot animation identity. */
    public static final BlendAnimationKey ACTIVATE = BlendAnimationKey.parse("consumer:activate");

    /** Example capability controlled by a deliberate provider author. */
    public static final BlendResourceId HOST_CAPABILITY = BlendResourceId.parse("blendlib:host_registration");

    private ApiConsumerFixture() {
    }

    /**
     * Retains the original public key-only consumer example.
     *
     * @param rawValue canonical semantic resource id
     * @return canonical validated id value
     */
    public static String canonicalModelId(String rawValue) {
        return BlendResourceId.parse(rawValue).value();
    }

    /**
     * Runs a complete stable-facade sample plus an explicitly opt-in capability freeze.
     *
     * <p>This isolated fixture installs the supplied adapter only for the duration of its own calls;
     * production platform bootstrap owns its real adapter lifecycle. The adapter must advertise a
     * compatible {@link #HOST_CAPABILITY} offer for the capability plan to publish.</p>
     *
     * @param adapter controlled platform adapter supplied by a provider author
     * @return immutable result containing the three semantic receipts, socket query, and frozen plan
     */
    public static FixtureResult runExample(PlatformAdapter adapter) {
        Objects.requireNonNull(adapter, "adapter");
        PlatformAdapterControl control = PlatformAdapterControl.global();
        control.install(adapter);
        try {
            RegistrationReceipt entity = BlendLib.entity("fixture-entity")
                    .model(MODEL)
                    .animation(host -> AnimationRequest.loop(IDLE).withSpeed(host.length() / 10.0D))
                    .register();
            RegistrationReceipt blockEntity = BlendLib.blockEntity("fixture-block-entity")
                    .model(MODEL)
                    .animation(AnimationRequest.once(ACTIVATE).withTransition(Duration.ofMillis(125L)))
                    .register();
            RegistrationReceipt item = BlendLib.item("fixture-item")
                    .model(MODEL)
                    .animation(AnimationRequest.loop(IDLE))
                    .register();

            ModelInstance instance = new ModelInstance(BlendInstanceKey.ephemeral("fixture-session", "fixture-entity"),
                    MODEL, 1L);
            SocketQuery socket = SocketQuery.of(instance, BlendResourceId.parse("consumer:tip"));

            CapabilityRegistry registry = new CapabilityRegistry();
            registry.register(adapter);
            CapabilityVersionRange range = new CapabilityVersionRange(CapabilityVersion.INITIAL_PROTOCOL,
                    new CapabilityVersion(2, 0, 0));
            registry.discover(java.util.List.of(CapabilityRequest.optional(HOST_CAPABILITY, range,
                    new CapabilityFallback(BlendResourceId.parse("blendlib:standard_host_registration"),
                            "The standard semantic host-registration path is equivalent"))));
            CapabilityPlan plan = registry.freeze(1L);
            plan.requirePublishable();
            return new FixtureResult(entity, blockEntity, item, socket, plan);
        } finally {
            control.uninstall();
        }
    }

    /**
     * Demonstrates fail-closed error handling when an ordinary registration has no installed adapter.
     *
     * @return structured stable diagnostic code from the rejected registration
     */
    public static BlendApiDiagnosticCode missingAdapterDiagnostic() {
        try {
            BlendLib.entity("unbound-fixture-entity")
                    .model(MODEL)
                    .animation(AnimationRequest.loop(IDLE))
                    .register();
            throw new IllegalStateException("Fixture expected adapter absence to fail closed");
        } catch (BlendRegistrationException exception) {
            return exception.diagnostic().code();
        }
    }

    /**
     * Demonstrates controlled capability error handling for a required unknown provider claim.
     *
     * @return stable experimental capability failure code
     */
    public static CapabilityErrorCode requiredCapabilityFailure() {
        CapabilityRegistry registry = new CapabilityRegistry();
        CapabilityVersionRange range = new CapabilityVersionRange(CapabilityVersion.INITIAL_PROTOCOL,
                new CapabilityVersion(2, 0, 0));
        registry.discover(java.util.List.of(CapabilityRequest.required(HOST_CAPABILITY, range)));
        try {
            registry.freeze(2L).requirePublishable();
            throw new IllegalStateException("Fixture expected unknown required capability to fail closed");
        } catch (CapabilityNegotiationException exception) {
            return exception.diagnostic().code();
        }
    }

    /**
     * Immutable result of the complete public consumer sample.
     *
     * @param entity entity registration acknowledgement
     * @param blockEntity block-entity registration acknowledgement
     * @param item item registration acknowledgement
     * @param socket semantic socket request that carries no pose/render object
     * @param capabilityPlan separately versioned experimental capability result
     */
    public record FixtureResult(
            RegistrationReceipt entity,
            RegistrationReceipt blockEntity,
            RegistrationReceipt item,
            SocketQuery socket,
            CapabilityPlan capabilityPlan) {
        /** Validates that the fixture exposes only immutable public result values. */
        public FixtureResult {
            entity = Objects.requireNonNull(entity, "entity");
            blockEntity = Objects.requireNonNull(blockEntity, "blockEntity");
            item = Objects.requireNonNull(item, "item");
            socket = Objects.requireNonNull(socket, "socket");
            capabilityPlan = Objects.requireNonNull(capabilityPlan, "capabilityPlan");
        }
    }
}
