package com.liy.blendlib.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.spi.experimental.CapabilityOffer;
import com.liy.blendlib.spi.experimental.CapabilityVersion;
import com.liy.blendlib.spi.experimental.PlatformAdapter;
import com.liy.blendlib.spi.experimental.PlatformAdapterControl;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StableFacadeContractTest {
    private static final BlendModelKey MODEL = BlendModelKey.parse("consumer:models/clockwork");
    private static final BlendAnimationKey IDLE = BlendAnimationKey.parse("consumer:idle");

    @Test
    void publicDiagnosticsSanitizeControlsAndExternalAdapterFailuresExposeNoCallerCause() {
        BlendApiDiagnostic diagnostic = new BlendApiDiagnostic(
                BlendApiDiagnosticCode.PLATFORM_ADAPTER_FAILURE,
                BlendDiagnosticSeverity.ERROR,
                "unsafe\u0000text\ud800tail");
        assertEquals("unsafe?text?tail", diagnostic.message());
        assertTrue(diagnostic.message().chars().noneMatch(value -> Character.isISOControl(value)
                || Character.isSurrogate((char) value)));

        PlatformAdapterControl control = PlatformAdapterControl.global();
        control.install(new HostileFailureAdapter());
        BlendRegistrationException failure = assertThrows(
                BlendRegistrationException.class,
                () -> BlendLib.entity("hostile-cause")
                        .model(MODEL)
                        .animation(AnimationRequest.loop(IDLE))
                        .register());
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains("attacker"));
    }

    @BeforeEach
    void clearGlobalAdapter() {
        PlatformAdapterControl.global().uninstall();
    }

    @AfterEach
    void clearGlobalAdapterAfterTest() {
        PlatformAdapterControl.global().uninstall();
    }

    @Test
    void builderFailsClosedForMissingConfigurationAndAbsentAdapter() {
        BlendRegistrationException missingModel = assertThrows(BlendRegistrationException.class,
                () -> BlendLib.entity("entity-host").animation(AnimationRequest.loop(IDLE)).build());
        assertEquals(BlendApiDiagnosticCode.MODEL_MISSING, missingModel.diagnostic().code());

        BlendRegistrationException missingAnimation = assertThrows(BlendRegistrationException.class,
                () -> BlendLib.blockEntity("block-host").model(MODEL).build());
        assertEquals(BlendApiDiagnosticCode.ANIMATION_MISSING, missingAnimation.diagnostic().code());

        BlendRegistrationException noAdapter = assertThrows(BlendRegistrationException.class,
                () -> BlendLib.item("item-host").model(MODEL).animation(AnimationRequest.loop(IDLE)).register());
        assertEquals(BlendApiDiagnosticCode.PLATFORM_ADAPTER_UNAVAILABLE, noAdapter.diagnostic().code());
    }

    @Test
    void facadeBuildsTypedEntityBlockEntityAndItemSpecifications() {
        RecordingAdapter adapter = new RecordingAdapter(false);
        PlatformAdapterControl.global().install(adapter);

        HostRegistrationSpec<String> entity = BlendLib.entity("entity-host")
                .model(MODEL)
                .animation(host -> AnimationRequest.loop(IDLE).withSpeed(host.length()))
                .build();
        HostRegistrationSpec<Integer> blockEntity = BlendLib.blockEntity(7)
                .model(MODEL)
                .animation(AnimationRequest.once(IDLE).withTransition(Duration.ofMillis(150L)))
                .build();
        HostRegistrationSpec<String> item = BlendLib.item("item-host")
                .model(MODEL)
                .animation(AnimationRequest.loop(IDLE))
                .build();

        assertEquals(HostKind.ENTITY, entity.hostKind());
        assertEquals(11.0D, entity.animationFor("entity-host").speed());
        assertEquals(HostKind.BLOCK_ENTITY, blockEntity.hostKind());
        assertEquals(PlaybackMode.ONCE, blockEntity.animationFor(7).playbackMode());
        assertEquals(HostKind.ITEM, item.hostKind());
        assertEquals(PlaybackMode.LOOP, item.animationFor("item-host").playbackMode());

        assertEquals(MODEL, BlendLib.entity("registered-entity").model(MODEL)
                .animation(AnimationRequest.loop(IDLE)).register().modelKey());
        assertEquals(MODEL, BlendLib.blockEntity("registered-block").model(MODEL)
                .animation(AnimationRequest.loop(IDLE)).register().modelKey());
        assertEquals(MODEL, BlendLib.item("registered-item").model(MODEL)
                .animation(AnimationRequest.loop(IDLE)).register().modelKey());
        assertEquals(3, adapter.registerCount);
        assertEquals(3, PlatformAdapterControl.global().registrationCount());
    }

    @Test
    void duplicateTargetIsExplicitlyRejectedInsteadOfSelectingRegistrationOrder() {
        RecordingAdapter adapter = new RecordingAdapter(false);
        PlatformAdapterControl.global().install(adapter);

        BlendLib.entity("same-host").model(MODEL).animation(AnimationRequest.loop(IDLE)).register();
        BlendRegistrationException duplicate = assertThrows(BlendRegistrationException.class,
                () -> BlendLib.entity("same-host").model(MODEL).animation(AnimationRequest.once(IDLE)).register());

        assertEquals(BlendApiDiagnosticCode.DUPLICATE_TARGET, duplicate.diagnostic().code());
        assertEquals(1, adapter.registerCount);
    }

    @Test
    void invalidAdapterReceiptFailsBeforeTheTargetIsRecorded() {
        RecordingAdapter adapter = new RecordingAdapter(true);
        PlatformAdapterControl.global().install(adapter);

        BlendRegistrationException invalidReceipt = assertThrows(BlendRegistrationException.class,
                () -> BlendLib.entity("host").model(MODEL).animation(AnimationRequest.loop(IDLE)).register());
        assertEquals(BlendApiDiagnosticCode.INVALID_PLATFORM_RECEIPT, invalidReceipt.diagnostic().code());
        assertEquals(0, PlatformAdapterControl.global().registrationCount());

        PlatformAdapterControl.global().uninstall();
        PlatformAdapterControl.global().install(new NullReceiptAdapter());
        BlendRegistrationException nullReceipt = assertThrows(BlendRegistrationException.class,
                () -> BlendLib.entity("null-host").model(MODEL).animation(AnimationRequest.loop(IDLE)).register());
        assertEquals(BlendApiDiagnosticCode.INVALID_PLATFORM_RECEIPT, nullReceipt.diagnostic().code());
        assertEquals(0, PlatformAdapterControl.global().registrationCount());
    }

    @Test
    void itemBuilderRejectsStatefulPlaybackAtBuildAndOnLaterSourceEvaluation() {
        BlendRegistrationException fixedOnce = assertThrows(BlendRegistrationException.class,
                () -> BlendLib.item("item-host").model(MODEL).animation(AnimationRequest.once(IDLE)).build());
        assertEquals(BlendApiDiagnosticCode.UNSUPPORTED_ITEM_ANIMATION, fixedOnce.diagnostic().code());

        boolean[] first = {true};
        HostRegistrationSpec<String> dynamic = BlendLib.item("dynamic-item")
                .model(MODEL)
                .animation(host -> first[0] ? AnimationRequest.loop(IDLE) : AnimationRequest.hold(IDLE))
                .build();
        first[0] = false;
        BlendRegistrationException laterHold = assertThrows(BlendRegistrationException.class,
                () -> dynamic.animationFor("dynamic-item"));
        assertEquals(BlendApiDiagnosticCode.UNSUPPORTED_ITEM_ANIMATION, laterHold.diagnostic().code());

        BlendRegistrationException rawSourceHold = assertThrows(BlendRegistrationException.class,
                () -> dynamic.animationSource().requestFor("dynamic-item"));
        assertEquals(BlendApiDiagnosticCode.UNSUPPORTED_ITEM_ANIMATION, rawSourceHold.diagnostic().code());
    }

    @Test
    void itemSourceAccessorValidatesEveryEvaluationWhileOtherHostsKeepTheirPlaybackModes() {
        AnimationRequest[] currentItemRequest = {AnimationRequest.loop(IDLE)};
        HostRegistrationSpec<String> item = BlendLib.item("item-accessor")
                .model(MODEL)
                .animation(host -> currentItemRequest[0])
                .build();

        assertEquals(PlaybackMode.LOOP, item.animationSource().requestFor("item-accessor").playbackMode());
        currentItemRequest[0] = AnimationRequest.once(IDLE);
        BlendRegistrationException once = assertThrows(BlendRegistrationException.class,
                () -> item.animationSource().requestFor("item-accessor"));
        assertEquals(BlendApiDiagnosticCode.UNSUPPORTED_ITEM_ANIMATION, once.diagnostic().code());

        HostRegistrationSpec<String> entity = BlendLib.entity("entity-accessor")
                .model(MODEL)
                .animation(AnimationRequest.once(IDLE))
                .build();
        HostRegistrationSpec<String> blockEntity = BlendLib.blockEntity("block-accessor")
                .model(MODEL)
                .animation(AnimationRequest.hold(IDLE))
                .build();
        assertEquals(PlaybackMode.ONCE, entity.animationSource().requestFor("entity-accessor").playbackMode());
        assertEquals(PlaybackMode.HOLD, blockEntity.animationSource().requestFor("block-accessor").playbackMode());
    }

    @Test
    void adapterSuppliedRegistrationDiagnosticsAreNormalizedAndBounded() {
        PlatformAdapterControl.global().install(new MisleadingAdapter());

        BlendRegistrationException normalized = assertThrows(BlendRegistrationException.class,
                () -> BlendLib.entity("host").model(MODEL).animation(AnimationRequest.loop(IDLE)).register());

        assertEquals(BlendApiDiagnosticCode.PLATFORM_ADAPTER_FAILURE, normalized.diagnostic().code());
        assertEquals(BlendDiagnosticSeverity.ERROR, normalized.diagnostic().severity());
        assertFalse(normalized.diagnostic().message().contains(BlendApiDiagnosticCode.MODEL_MISSING.code()));
        assertTrue(normalized.diagnostic().message().length() <= BlendApiDiagnostic.MAX_MESSAGE_LENGTH);
        assertNull(normalized.getCause());
        assertEquals(0, PlatformAdapterControl.global().registrationCount());
    }

    @Test
    void semanticValuesAreImmutableAndKeepResourceInstanceSnapshotRolesSeparate() {
        ModelInstance instance = new ModelInstance(BlendInstanceKey.entity("session-a", 42), MODEL, 3L);
        SocketQuery socket = SocketQuery.of(instance, BlendResourceId.parse("consumer:tip"));
        AnimationRequest request = AnimationRequest.once(IDLE)
                .withSpeed(2.0D)
                .withTransition(Duration.ofMillis(250L));

        assertEquals(instance, socket.modelInstance());
        assertEquals("consumer:tip", socket.socketId().value());
        assertEquals(IDLE, request.animation());
        assertEquals(PlaybackMode.ONCE, request.playbackMode());
        assertThrows(IllegalArgumentException.class, () -> new ModelInstance(instance.instanceKey(), MODEL, -1L));
        assertThrows(IllegalArgumentException.class, () -> AnimationRequest.loop(IDLE).withSpeed(0.0D));
        assertThrows(IllegalArgumentException.class, () -> AnimationRequest.loop(IDLE).withSpeed(65.0D));
        assertThrows(IllegalArgumentException.class,
                () -> AnimationRequest.loop(IDLE).withTransition(Duration.ofSeconds(61L)));
        assertFalse(request.transition().isNegative());
    }

    private static final class RecordingAdapter implements PlatformAdapter {
        private final boolean inconsistentReceipt;
        private int registerCount;

        private RecordingAdapter(boolean inconsistentReceipt) {
            this.inconsistentReceipt = inconsistentReceipt;
        }

        @Override
        public BlendResourceId providerId() {
            return BlendResourceId.parse("consumer:platform_adapter");
        }

        @Override
        public Collection<CapabilityOffer> offers() {
            return List.of(new CapabilityOffer(providerId(), BlendResourceId.parse("blendlib:host_registration"),
                    CapabilityVersion.INITIAL_PROTOCOL, 0));
        }

        @Override
        public <H> RegistrationReceipt register(HostRegistrationSpec<H> specification) {
            registerCount++;
            if (inconsistentReceipt) {
                return new RegistrationReceipt(providerId(), HostKind.ITEM, specification.model());
            }
            return new RegistrationReceipt(providerId(), specification.hostKind(), specification.model());
        }
    }

    private static final class MisleadingAdapter implements PlatformAdapter {
        @Override
        public BlendResourceId providerId() {
            return BlendResourceId.parse("consumer:misleading_adapter");
        }

        @Override
        public Collection<CapabilityOffer> offers() {
            return List.of();
        }

        @Override
        public <H> RegistrationReceipt register(HostRegistrationSpec<H> specification) {
            throw new BlendRegistrationException(new BlendApiDiagnostic(
                    BlendApiDiagnosticCode.MODEL_MISSING,
                    BlendDiagnosticSeverity.WARNING,
                    "x".repeat(BlendApiDiagnostic.MAX_MESSAGE_LENGTH)));
        }
    }

    private static final class NullReceiptAdapter implements PlatformAdapter {
        @Override
        public BlendResourceId providerId() {
            return BlendResourceId.parse("consumer:null_receipt_adapter");
        }

        @Override
        public Collection<CapabilityOffer> offers() {
            return List.of();
        }

        @Override
        public <H> RegistrationReceipt register(HostRegistrationSpec<H> specification) {
            return null;
        }
    }

    private static final class HostileFailureAdapter implements PlatformAdapter {
        @Override
        public BlendResourceId providerId() {
            return BlendResourceId.parse("consumer:hostile_failure_adapter");
        }

        @Override
        public Collection<CapabilityOffer> offers() {
            return List.of();
        }

        @Override
        public <H> RegistrationReceipt register(HostRegistrationSpec<H> specification) {
            throw new HostileAdapterFailure();
        }
    }

    private static final class HostileAdapterFailure extends AssertionError {
        @SuppressWarnings("serial")
        private static final long serialVersionUID = 1L;

        @Override
        public String getMessage() {
            throw new IllegalStateException("attacker getMessage");
        }

        @Override
        public String toString() {
            throw new IllegalStateException("attacker toString");
        }
    }
}
