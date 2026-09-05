package com.liy.blendlib.fabric.client.reload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class X7PolicyResourceRecordFuseTest {
    @Test
    void exactD1RecordFuseIsIdempotentAndDoesNotNeedAnExternalCache() {
        ModelRegistryGeneration generation = ModelRegistryGeneration.empty(71L);
        AtomicInteger physicalCloseCalls = new AtomicInteger();
        CompletedGenerationResourceSet aggregate = X7GenerationResourceTestSupport.complete(
                generation, 1, physicalCloseCalls::incrementAndGet);
        X7PolicyResourceRecord exactRecord = X7PolicyResourceRecord.forCanonicalCompleteSet(generation, aggregate);

        assertFalse(exactRecord.isFutureGpuDisabled());
        assertTrue(X7PublishedSubmissionBridge.disableFutureGpu(exactRecord));
        assertTrue(exactRecord.isFutureGpuDisabled());
        assertFalse(X7PublishedSubmissionBridge.disableFutureGpu(exactRecord));

        assertTrue(aggregate.closeIfStillCallerOwned());
        assertEquals(1, physicalCloseCalls.get());
    }
}
