package com.liy.blendlib.fabric.client.reload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class X7GpuResourceFactoryTest {
    @Test
    void successfulPairPublishesOnlyTransactionSuccessAndAlwaysReleasesCpuStaging() {
        X7GpuTestFixtures.FakeGpuDevice device = new X7GpuTestFixtures.FakeGpuDevice();
        X7GeometryStaging staging = X7GpuTestFixtures.staging();

        X7GpuResourceFactory.Attempt attempt = X7GpuResourceFactory.prepare(
                device, X7GpuTestFixtures.key(), staging);

        assertEquals(X7GpuResourceFactory.Attempt.Outcome.SUCCESS, attempt.outcome());
        assertNull(attempt.failureOrNull());
        assertTrue(staging.isClosed());
        assertEquals(1, device.vertexCreates);
        assertEquals(1, device.indexCreates);
        assertEquals(ByteOrder.LITTLE_ENDIAN, device.vertexUpload.order());
        assertEquals(ByteOrder.LITTLE_ENDIAN, device.indexUpload.order());
        X7SharedGeometryResources resources = attempt.resourcesOrNull();
        assertEquals(X7GpuTestFixtures.key(), resources.key());
        assertEquals(3, resources.vertexCount());
        assertEquals(3, resources.indexCount());
        assertEquals(96, resources.vertexBytes());
        assertEquals(12, resources.indexBytes());
        assertFalse(resources.isClosed());
    }

    @Test
    void renderThreadAssertionRuntimeFailureClosesStagingBeforeReturningResourceFailure() {
        X7GpuTestFixtures.FakeGpuDevice device = new X7GpuTestFixtures.FakeGpuDevice();
        RuntimeException expected = new IllegalStateException("wrong render owner");
        device.assertFailure = expected;
        X7GeometryStaging staging = X7GpuTestFixtures.staging();

        X7GpuResourceFactory.Attempt attempt = X7GpuResourceFactory.prepare(
                device, X7GpuTestFixtures.key(), staging);

        assertFailure(attempt, X7GpuResourceFactory.Attempt.FailureStage.RENDER_THREAD_ASSERTION, expected);
        assertTrue(staging.isClosed());
        assertEquals(0, device.vertexCreates);
        assertEquals(0, device.indexCreates);
    }

    @Test
    void assertionErrorRethrowsItsExactIdentityAfterStagingCleanup() {
        X7GpuTestFixtures.FakeGpuDevice device = new X7GpuTestFixtures.FakeGpuDevice();
        AssertionError expected = new AssertionError("render assertion error");
        device.assertFailure = expected;
        X7GeometryStaging staging = X7GpuTestFixtures.staging();

        AssertionError actual = assertThrows(AssertionError.class,
                () -> X7GpuResourceFactory.prepare(device, X7GpuTestFixtures.key(), staging));

        assertSame(expected, actual);
        assertTrue(staging.isClosed());
        assertTrue(device.buffers.isEmpty());
    }

    @Test
    void firstCleanupLedgerAllocationOutOfMemoryRethrowsExactlyAfterStagingCleanup() {
        X7GpuTestFixtures.FakeGpuDevice device = new X7GpuTestFixtures.FakeGpuDevice();
        X7GeometryStaging staging = X7GpuTestFixtures.staging();
        OutOfMemoryError expected = new OutOfMemoryError("cleanup ledger exhausted");

        OutOfMemoryError actual = assertThrows(OutOfMemoryError.class,
                () -> X7GpuResourceFactory.prepare(
                        device,
                        X7GpuTestFixtures.key(),
                        staging,
                        X7SharedGeometryResources::new,
                        () -> {
                            assertFalse(staging.isClosed());
                            throw expected;
                        }));

        assertSame(expected, actual);
        assertEquals(0, actual.getSuppressed().length);
        assertTrue(staging.isClosed());
        assertEquals(0, device.assertCalls);
        assertEquals(0, device.vertexCreates);
        assertEquals(0, device.indexCreates);
    }

    @Test
    void vertexRuntimeFailureReturnsResourceFailureWithoutPublishingAnyBuffer() {
        X7GpuTestFixtures.FakeGpuDevice device = new X7GpuTestFixtures.FakeGpuDevice();
        RuntimeException expected = new IllegalStateException("vertex allocation failed");
        device.vertexFailure = expected;
        X7GeometryStaging staging = X7GpuTestFixtures.staging();

        X7GpuResourceFactory.Attempt attempt = X7GpuResourceFactory.prepare(
                device, X7GpuTestFixtures.key(), staging);

        assertFailure(attempt, X7GpuResourceFactory.Attempt.FailureStage.VERTEX_ALLOCATION, expected);
        assertTrue(staging.isClosed());
        assertEquals(1, device.vertexCreates);
        assertEquals(0, device.indexCreates);
        assertTrue(device.buffers.isEmpty());
    }

    @Test
    void vertexOutOfMemoryErrorRethrowsItsExactIdentityAfterStagingCleanup() {
        X7GpuTestFixtures.FakeGpuDevice device = new X7GpuTestFixtures.FakeGpuDevice();
        OutOfMemoryError expected = new OutOfMemoryError("vertex allocation exhausted");
        device.vertexFailure = expected;
        X7GeometryStaging staging = X7GpuTestFixtures.staging();

        OutOfMemoryError actual = assertThrows(OutOfMemoryError.class,
                () -> X7GpuResourceFactory.prepare(device, X7GpuTestFixtures.key(), staging));

        assertSame(expected, actual);
        assertTrue(staging.isClosed());
        assertTrue(device.buffers.isEmpty());
    }

    @Test
    void indexRuntimeFailureClosesThePriorVertexExactlyOnceAndReturnsResourceFailure() {
        X7GpuTestFixtures.FakeGpuDevice device = new X7GpuTestFixtures.FakeGpuDevice();
        RuntimeException expected = new IllegalStateException("index allocation failed");
        device.indexFailure = expected;
        X7GeometryStaging staging = X7GpuTestFixtures.staging();

        X7GpuResourceFactory.Attempt attempt = X7GpuResourceFactory.prepare(
                device, X7GpuTestFixtures.key(), staging);

        assertFailure(attempt, X7GpuResourceFactory.Attempt.FailureStage.INDEX_ALLOCATION, expected);
        assertTrue(staging.isClosed());
        assertEquals(1, device.vertexCreates);
        assertEquals(1, device.indexCreates);
        assertEquals(1, device.buffers.size());
        assertTrue(device.buffers.getFirst().closed);
        assertEquals(1, device.buffers.getFirst().closeCalls);
    }

    @Test
    void indexErrorClosesThePriorVertexThenRethrowsItsExactIdentity() {
        X7GpuTestFixtures.FakeGpuDevice device = new X7GpuTestFixtures.FakeGpuDevice();
        AssertionError expected = new AssertionError("index allocation error");
        device.indexFailure = expected;
        X7GeometryStaging staging = X7GpuTestFixtures.staging();

        AssertionError actual = assertThrows(AssertionError.class,
                () -> X7GpuResourceFactory.prepare(device, X7GpuTestFixtures.key(), staging));

        assertSame(expected, actual);
        assertTrue(staging.isClosed());
        assertEquals(1, device.buffers.size());
        assertEquals(1, device.buffers.getFirst().closeCalls);
    }

    @Test
    void resourceConstructionRuntimeFailureClosesBothBuffersExactlyOnce() {
        X7GpuTestFixtures.FakeGpuDevice device = new X7GpuTestFixtures.FakeGpuDevice();
        RuntimeException expected = new IllegalStateException("resource construction failed");
        X7GeometryStaging staging = X7GpuTestFixtures.staging();

        X7GpuResourceFactory.Attempt attempt = X7GpuResourceFactory.prepare(
                device,
                X7GpuTestFixtures.key(),
                staging,
                (key, vertexFormat, indexType, primitiveMode, vertexCount, indexCount, vertexBytes, indexBytes, vertex, index) -> {
                    throw expected;
                });

        assertFailure(attempt, X7GpuResourceFactory.Attempt.FailureStage.RESOURCE_CONSTRUCTION, expected);
        assertTrue(staging.isClosed());
        assertEquals(2, device.buffers.size());
        for (X7GpuTestFixtures.FakeGpuBuffer buffer : device.buffers) {
            assertTrue(buffer.closed);
            assertEquals(1, buffer.closeCalls);
        }
    }

    @Test
    void isClosedRuntimeAndErrorFailpointsAreNeverUsedAsCleanupGates() {
        assertCleanupAvoidsIsClosed(new IllegalStateException("isClosed runtime"));
        assertCleanupAvoidsIsClosed(new AssertionError("isClosed error"));
    }

    @Test
    void cleanupRuntimeFailureIsRecordedAndSuppressedWithoutHidingTheAllocationCause() {
        X7GpuTestFixtures.FakeGpuDevice device = new X7GpuTestFixtures.FakeGpuDevice();
        RuntimeException allocationFailure = new IllegalStateException("index allocation failed");
        RuntimeException cleanupFailure = new IllegalStateException("vertex close failed");
        device.indexFailure = allocationFailure;
        device.onVertexAllocated = buffer -> buffer.closeFailure = cleanupFailure;
        X7GeometryStaging staging = X7GpuTestFixtures.staging();

        X7GpuResourceFactory.Attempt attempt = X7GpuResourceFactory.prepare(
                device, X7GpuTestFixtures.key(), staging);

        assertFailure(attempt, X7GpuResourceFactory.Attempt.FailureStage.INDEX_ALLOCATION, allocationFailure);
        assertSame(cleanupFailure, attempt.failureOrNull().cleanupOrNull());
        assertEquals(1, allocationFailure.getSuppressed().length);
        assertSame(cleanupFailure, allocationFailure.getSuppressed()[0]);
        assertTrue(staging.isClosed());
        assertEquals(1, device.buffers.getFirst().closeCalls);
    }

    @Test
    void cleanupErrorAndOutOfMemoryErrorBecomePrimaryAndRetainTheAllocationFailure() {
        assertFatalCleanup(new AssertionError("vertex close error"));
        assertFatalCleanup(new OutOfMemoryError("vertex close exhausted"));
    }

    private static void assertCleanupAvoidsIsClosed(Throwable isClosedFailure) {
        X7GpuTestFixtures.FakeGpuDevice device = new X7GpuTestFixtures.FakeGpuDevice();
        RuntimeException allocationFailure = new IllegalStateException("index allocation failed");
        device.indexFailure = allocationFailure;
        device.onVertexAllocated = buffer -> buffer.isClosedFailure = isClosedFailure;
        X7GeometryStaging staging = X7GpuTestFixtures.staging();

        X7GpuResourceFactory.Attempt attempt = X7GpuResourceFactory.prepare(
                device, X7GpuTestFixtures.key(), staging);

        assertFailure(attempt, X7GpuResourceFactory.Attempt.FailureStage.INDEX_ALLOCATION, allocationFailure);
        assertEquals(0, device.buffers.getFirst().isClosedCalls);
        assertEquals(1, device.buffers.getFirst().closeCalls);
        assertTrue(device.buffers.getFirst().closed);
    }

    private static void assertFatalCleanup(Error cleanupFailure) {
        X7GpuTestFixtures.FakeGpuDevice device = new X7GpuTestFixtures.FakeGpuDevice();
        RuntimeException allocationFailure = new IllegalStateException("index allocation failed");
        device.indexFailure = allocationFailure;
        device.onVertexAllocated = buffer -> buffer.closeFailure = cleanupFailure;
        X7GeometryStaging staging = X7GpuTestFixtures.staging();

        Error actual = assertThrows(cleanupFailure.getClass(),
                () -> X7GpuResourceFactory.prepare(device, X7GpuTestFixtures.key(), staging));

        assertSame(cleanupFailure, actual);
        assertEquals(1, actual.getSuppressed().length);
        assertSame(allocationFailure, actual.getSuppressed()[0]);
        assertTrue(staging.isClosed());
        assertEquals(1, device.buffers.getFirst().closeCalls);
    }

    private static void assertFailure(
            X7GpuResourceFactory.Attempt attempt,
            X7GpuResourceFactory.Attempt.FailureStage stage,
            Throwable cause) {
        assertEquals(X7GpuResourceFactory.Attempt.Outcome.RESOURCE_FAILURE, attempt.outcome());
        assertNull(attempt.resourcesOrNull());
        assertEquals(stage, attempt.failureOrNull().stage());
        assertSame(cause, attempt.failureOrNull().cause());
    }
}
