package com.liy.blendlib.fabric.client.host;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.spi.experimental.BlendProvider;
import com.liy.blendlib.spi.experimental.CapabilityOffer;
import com.liy.blendlib.spi.experimental.CapabilityRegistry;
import com.liy.blendlib.spi.experimental.CapabilityRequest;
import com.liy.blendlib.spi.experimental.CapabilityVersion;
import com.liy.blendlib.spi.experimental.CapabilityVersionRange;
import com.liy.blendlib.spi.experimental.ProviderLifecycleContext;
import com.liy.blendlib.spi.experimental.ProviderLifecycleSession;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Test-only construction of a real published X1 generation session; never a fake X4 lease. */
final class X4LifecycleTestSupport {
    private static final AtomicLong SEQUENCE = new AtomicLong();

    private X4LifecycleTestSupport() {
    }

    static SessionHarness published(long generation) {
        long sequence = SEQUENCE.incrementAndGet();
        BlendResourceId providerId = BlendResourceId.parse("x4_test:lifecycle/provider/" + sequence);
        BlendResourceId capabilityId = BlendResourceId.parse("x4_test:lifecycle/capability/" + sequence);
        ProbeProvider provider = new ProbeProvider(providerId, capabilityId);
        CapabilityRegistry registry = new CapabilityRegistry();
        registry.register(provider);
        registry.discover(List.of(CapabilityRequest.required(
                capabilityId,
                new CapabilityVersionRange(CapabilityVersion.INITIAL_PROTOCOL, new CapabilityVersion(2, 0, 0)))));
        ProviderLifecycleSession session = new ProviderLifecycleSession(registry.freeze(generation), List.of(provider));
        if (!session.prepare().successful() || !session.apply().successful()) {
            throw new AssertionError("test generation session did not publish cleanly");
        }
        session.publish();
        return new SessionHarness(session, provider);
    }

    record SessionHarness(ProviderLifecycleSession session, ProbeProvider provider) {
    }

    static final class ProbeProvider implements BlendProvider {
        private final BlendResourceId providerId;
        private final Collection<CapabilityOffer> offers;
        private final AtomicInteger retireCalls = new AtomicInteger();
        private final AtomicInteger closeCalls = new AtomicInteger();
        private volatile Runnable retireAction = () -> { };
        private volatile Runnable closeAction = () -> { };

        private ProbeProvider(BlendResourceId providerId, BlendResourceId capabilityId) {
            this.providerId = providerId;
            offers = List.of(new CapabilityOffer(providerId, capabilityId, CapabilityVersion.INITIAL_PROTOCOL, 1));
        }

        @Override
        public BlendResourceId providerId() {
            return providerId;
        }

        @Override
        public Collection<CapabilityOffer> offers() {
            return offers;
        }

        @Override
        public void retire(ProviderLifecycleContext context) {
            retireCalls.incrementAndGet();
            retireAction.run();
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            closeAction.run();
        }

        void onRetire(Runnable action) {
            retireAction = action;
        }

        void onClose(Runnable action) {
            closeAction = action;
        }

        int retireCalls() {
            return retireCalls.get();
        }

        int closeCalls() {
            return closeCalls.get();
        }
    }
}
