package com.liy.blendlib.spi.experimental;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Locks the current host protocol boundary independently from stable-facade compatibility. */
class ExperimentalProtocolVersionContractTest {
    private static final BlendResourceId PROVIDER = BlendResourceId.parse("x1_protocol:provider");
    private static final BlendResourceId CAPABILITY = BlendResourceId.parse("x1_protocol:capability");

    @Test
    void currentHostProtocolConstantsDeclareTheVersionedBoundary() {
        assertEquals("1.1.0", ExperimentalBlendLibSpi.CURRENT_PROTOCOL);
        assertEquals("1.1.0", CapabilityVersion.CURRENT_PROTOCOL.toString());
        assertEquals(new CapabilityVersion(1, 0, 0), CapabilityVersion.INITIAL_PROTOCOL);
        assertTrue(CapabilityVersion.CURRENT_PROTOCOL_RANGE.contains(CapabilityVersion.CURRENT_PROTOCOL));
        assertFalse(CapabilityVersion.CURRENT_PROTOCOL_RANGE.contains(CapabilityVersion.INITIAL_PROTOCOL));
    }

    @Test
    void genericRegistryCanRepresentHistoricalProtocolDataWithoutGrantingHistoricalFatalContainment() {
        TrackingProvider provider = new TrackingProvider(CapabilityVersion.INITIAL_PROTOCOL);
        CapabilityRegistry registry = new CapabilityRegistry();

        registry.register(provider);
        assertEquals(
                List.of(new CapabilityOffer(PROVIDER, CAPABILITY, CapabilityVersion.INITIAL_PROTOCOL, 1)),
                registry.discover(List.of(CapabilityRequest.required(
                        CAPABILITY,
                        new CapabilityVersionRange(
                                CapabilityVersion.INITIAL_PROTOCOL, new CapabilityVersion(1, 1, 0))))));

        assertEquals(List.of(PROVIDER), registry.registeredProviderIds());
        assertEquals(1, provider.offerCalls.get());
        assertEquals(0, provider.prepareCalls.get());
        assertEquals(0, provider.applyCalls.get());
        assertEquals(0, provider.retireCalls.get());
        assertEquals(0, provider.closeCalls.get());
    }

    @Test
    void currentHostClassifiesEveryErrorAsTerminalByExactIdentity() {
        AssertionError assertion = new AssertionError("current-protocol assertion");
        LinkageError linkage = new LinkageError("current-protocol linkage");
        RuntimeException ordinary = new IllegalStateException("current-protocol ordinary");

        assertTrue(ExperimentalControlBoundary.isFatal(assertion));
        assertTrue(ExperimentalControlBoundary.isFatal(linkage));
        assertFalse(ExperimentalControlBoundary.isFatal(ordinary));
        assertSame(assertion, assertThrows(AssertionError.class,
                () -> ExperimentalControlBoundary.rethrowIfFatal(assertion)));
        assertSame(linkage, assertThrows(LinkageError.class,
                () -> ExperimentalControlBoundary.rethrowIfFatal(linkage)));
    }

    private static final class TrackingProvider implements BlendProvider {
        private final CapabilityVersion protocolVersion;
        private final AtomicInteger offerCalls = new AtomicInteger();
        private final AtomicInteger prepareCalls = new AtomicInteger();
        private final AtomicInteger applyCalls = new AtomicInteger();
        private final AtomicInteger retireCalls = new AtomicInteger();
        private final AtomicInteger closeCalls = new AtomicInteger();

        private TrackingProvider(CapabilityVersion protocolVersion) {
            this.protocolVersion = protocolVersion;
        }

        @Override
        public BlendResourceId providerId() {
            return PROVIDER;
        }

        @Override
        public Collection<CapabilityOffer> offers() {
            offerCalls.incrementAndGet();
            return List.of(new CapabilityOffer(PROVIDER, CAPABILITY, protocolVersion, 1));
        }

        @Override
        public void prepare(ProviderLifecycleContext context) {
            prepareCalls.incrementAndGet();
        }

        @Override
        public void apply(ProviderLifecycleContext context) {
            applyCalls.incrementAndGet();
        }

        @Override
        public void retire(ProviderLifecycleContext context) {
            retireCalls.incrementAndGet();
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }
    }
}
