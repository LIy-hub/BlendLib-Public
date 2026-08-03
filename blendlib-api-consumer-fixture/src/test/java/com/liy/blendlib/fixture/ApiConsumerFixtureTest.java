package com.liy.blendlib.fixture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendApiDiagnosticCode;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.api.HostRegistrationSpec;
import com.liy.blendlib.api.RegistrationReceipt;
import com.liy.blendlib.spi.experimental.CapabilityOffer;
import com.liy.blendlib.spi.experimental.CapabilityErrorCode;
import com.liy.blendlib.spi.experimental.CapabilityVersion;
import com.liy.blendlib.spi.experimental.PlatformAdapter;
import com.liy.blendlib.spi.experimental.PlatformAdapterControl;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApiConsumerFixtureTest {
    @BeforeEach
    @AfterEach
    void clearGlobalAdapter() {
        PlatformAdapterControl.global().uninstall();
    }

    @Test
    void compilesAndUsesOnlyThePublicApiValueContract() {
        assertEquals("consumer:models/fixture", ApiConsumerFixture.canonicalModelId("consumer:models/fixture"));
    }

    @Test
    void runsEntityBlockEntityItemSocketAndControlledCapabilitySample() {
        ApiConsumerFixture.FixtureResult result = ApiConsumerFixture.runExample(new FixtureAdapter());

        assertEquals(ApiConsumerFixture.MODEL, result.entity().modelKey());
        assertEquals(ApiConsumerFixture.MODEL, result.blockEntity().modelKey());
        assertEquals(ApiConsumerFixture.MODEL, result.item().modelKey());
        assertEquals("consumer:tip", result.socket().socketId().value());
        assertTrue(result.capabilityPlan().isPublishable());
        assertFalse(result.capabilityPlan().selectedOffersInReportingOrder().isEmpty());
        assertEquals(BlendApiDiagnosticCode.PLATFORM_ADAPTER_UNAVAILABLE,
                ApiConsumerFixture.missingAdapterDiagnostic());
        assertEquals(CapabilityErrorCode.REQUIRED_UNSUPPORTED,
                ApiConsumerFixture.requiredCapabilityFailure());
    }

    private static final class FixtureAdapter implements PlatformAdapter {
        @Override
        public BlendResourceId providerId() {
            return BlendResourceId.parse("consumer:fixture_adapter");
        }

        @Override
        public Collection<CapabilityOffer> offers() {
            return List.of(new CapabilityOffer(providerId(), ApiConsumerFixture.HOST_CAPABILITY,
                    CapabilityVersion.INITIAL_PROTOCOL, 10));
        }

        @Override
        public <H> RegistrationReceipt register(HostRegistrationSpec<H> specification) {
            return new RegistrationReceipt(providerId(), specification.hostKind(), specification.model());
        }
    }
}
