package com.liy.blendlib.spi.experimental;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ReviewerTerminalCompletionProbeTest {
    @Test
    void completedOrdinaryRetirementReturnsThePublishedCompletionResultInstance() {
        BlendProvider provider = provider("review:ordinary_provider", false, null);
        ProviderLifecycleSession session = session(provider, 2L, "review:ordinary_capability");
        session.prepare();
        session.apply();
        session.publish();

        ProviderLifecycleResult publishedResult = session.retire();
        assertSame(publishedResult, session.retire());
    }

    @Test
    @SuppressWarnings("removal")
    void completedFatalPrepareRemainsObservableToLaterRetireAndCloseCallers() {
        ThreadDeath fatal = new ThreadDeath();
        BlendProvider provider = provider("review:fatal_provider", true, fatal);

        ProviderLifecycleSession session = session(provider, 1L, "review:fatal_capability");

        assertSame(fatal, assertThrows(ThreadDeath.class, session::prepare));
        assertSame(fatal, assertThrows(ThreadDeath.class, session::retire));
        assertSame(fatal, assertThrows(ThreadDeath.class, session::close));
    }

    @SuppressWarnings("removal")
    private static BlendProvider provider(String providerId, boolean failPrepare, ThreadDeath fatal) {
        return new BlendProvider() {
            @Override
            public BlendResourceId providerId() {
                return BlendResourceId.parse(providerId);
            }

            @Override
            public Collection<CapabilityOffer> offers() {
                return List.of(new CapabilityOffer(
                        providerId(),
                        BlendResourceId.parse(providerId.replace("_provider", "_capability")),
                        CapabilityVersion.INITIAL_PROTOCOL,
                        1));
            }

            @Override
            public void prepare(ProviderLifecycleContext context) {
                if (failPrepare) {
                    throw fatal;
                }
            }
        };
    }

    private static ProviderLifecycleSession session(BlendProvider provider, long generation, String capabilityId) {
        CapabilityRegistry registry = new CapabilityRegistry();
        registry.register(provider);
        registry.discover(List.of(CapabilityRequest.required(
                BlendResourceId.parse(capabilityId),
                new CapabilityVersionRange(
                        CapabilityVersion.INITIAL_PROTOCOL,
                        new CapabilityVersion(2, 0, 0)))));
        return new ProviderLifecycleSession(registry.freeze(generation), List.of(provider));
    }
}
