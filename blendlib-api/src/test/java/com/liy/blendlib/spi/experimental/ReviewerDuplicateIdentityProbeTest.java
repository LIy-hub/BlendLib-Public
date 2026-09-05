package com.liy.blendlib.spi.experimental;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class ReviewerDuplicateIdentityProbeTest {
    @Test
    void duplicateProviderIdIsRejectedBeforeTheDuplicateOfferCallbackRuns() {
        CapabilityRegistry registry = new CapabilityRegistry();
        registry.register(provider(false, new AtomicInteger()));
        AtomicInteger duplicateOfferCalls = new AtomicInteger();

        CapabilityNegotiationException duplicate = assertThrows(
                CapabilityNegotiationException.class,
                () -> registry.register(provider(true, duplicateOfferCalls)));

        assertEquals(CapabilityErrorCode.DUPLICATE_PROVIDER_ID, duplicate.diagnostic().code());
        assertEquals(0, duplicateOfferCalls.get());
    }

    private static BlendProvider provider(boolean failOffers, AtomicInteger offerCalls) {
        return new BlendProvider() {
            @Override
            public BlendResourceId providerId() {
                return BlendResourceId.parse("review:duplicate");
            }

            @Override
            public Collection<CapabilityOffer> offers() {
                offerCalls.incrementAndGet();
                if (failOffers) {
                    throw new IllegalStateException("duplicate offer callback must not run");
                }
                return List.of();
            }
        };
    }
}
