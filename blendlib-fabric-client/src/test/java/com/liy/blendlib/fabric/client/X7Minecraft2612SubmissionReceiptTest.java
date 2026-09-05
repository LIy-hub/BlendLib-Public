package com.liy.blendlib.fabric.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class X7Minecraft2612SubmissionReceiptTest {
    @Test
    void closedPassCreatesAnOwnedFenceAndSchedulerDispatchIsNotCompletion() {
        List<String> order = new ArrayList<>();
        FakeFence fence = new FakeFence(false, true);
        ClosedPassFenceFactory encoder = new ClosedPassFenceFactory(fence);
        encoder.passClosed = true;
        ManualScheduler scheduler = new ManualScheduler();
        int[] consumed = {0};

        X7Minecraft2612SubmissionReceipt receipt = X7Minecraft2612SubmissionReceipt.afterClosedPass(
                () -> {
                    order.add("createFence");
                    return encoder.createFence();
                },
                scheduler,
                () -> order.add("assert"),
                () -> consumed[0]++);

        assertEquals(List.of("assert", "createFence"), order);
        assertEquals(X7Minecraft2612SubmissionReceipt.State.FENCE_PENDING, receipt.state());
        assertEquals(1, encoder.creations);
        assertEquals(1, scheduler.pendingCount());
        assertEquals(0, consumed[0]);
        assertEquals(0, fence.closeCalls);

        scheduler.dispatchNext();
        assertEquals(X7Minecraft2612SubmissionReceipt.State.FENCE_PENDING, receipt.state());
        assertEquals(0, consumed[0]);
        assertEquals(1, scheduler.pendingCount());
        assertEquals(0, fence.closeCalls);

        Runnable completionDelivery = scheduler.takeNext();
        completionDelivery.run();
        completionDelivery.run();

        assertEquals(X7Minecraft2612SubmissionReceipt.State.FENCE_SIGNALED, receipt.state());
        assertEquals(1, consumed[0]);
        assertEquals(List.of(0L, 0L), fence.awaitTimeouts);
        assertEquals(1, fence.closeCalls);
        assertTrue(receipt.fenceClosed());
    }

    @Test
    void openPassIsRejectedBeforeAReceiptCanScheduleOrConsume() {
        FakeFence fence = new FakeFence(true);
        ClosedPassFenceFactory encoder = new ClosedPassFenceFactory(fence);
        ManualScheduler scheduler = new ManualScheduler();
        int[] consumed = {0};

        X7Minecraft2612SubmissionReceipt receipt = X7Minecraft2612SubmissionReceipt.afterClosedPass(
                encoder, scheduler, () -> { }, () -> consumed[0]++);

        assertEquals(X7Minecraft2612SubmissionReceipt.State.REJECTED, receipt.state());
        assertEquals(1, encoder.creations);
        assertEquals(0, scheduler.pendingCount());
        assertEquals(0, consumed[0]);
        assertTrue(receipt.failure() instanceof IllegalStateException);
    }

    @Test
    void schedulerFactoryAndAwaitFailuresKeepTheConsumerUnconsumedAndCloseOnlyTheOwnedFence() {
        IllegalStateException factoryFailure = new IllegalStateException("pass was not closed");
        X7Minecraft2612SubmissionReceipt factoryRejected = X7Minecraft2612SubmissionReceipt.afterClosedPass(
                () -> {
                    throw factoryFailure;
                },
                task -> {
                    throw new AssertionError("must not schedule");
                },
                () -> { },
                () -> {
                    throw new AssertionError("must not consume");
                });
        assertEquals(X7Minecraft2612SubmissionReceipt.State.REJECTED, factoryRejected.state());
        assertSame(factoryFailure, factoryRejected.failure());

        FakeFence queueFence = new FakeFence(true);
        IllegalStateException queueFailure = new IllegalStateException("scheduler failure");
        X7Minecraft2612SubmissionReceipt queueRejected = X7Minecraft2612SubmissionReceipt.afterClosedPass(
                () -> queueFence,
                task -> {
                    throw queueFailure;
                },
                () -> { },
                () -> {
                    throw new AssertionError("must not consume");
                });
        assertEquals(X7Minecraft2612SubmissionReceipt.State.REJECTED, queueRejected.state());
        assertSame(queueFailure, queueRejected.failure());
        assertEquals(1, queueFence.closeCalls);

        IllegalStateException awaitFailure = new IllegalStateException("own fence wait failure");
        FakeFence failingFence = new FakeFence(awaitFailure);
        ManualScheduler scheduler = new ManualScheduler();
        X7Minecraft2612SubmissionReceipt fenceFailed = X7Minecraft2612SubmissionReceipt.afterClosedPass(
                () -> failingFence,
                scheduler,
                () -> { },
                () -> {
                    throw new AssertionError("must not consume");
                });
        scheduler.dispatchNext();
        assertEquals(X7Minecraft2612SubmissionReceipt.State.FENCE_FAILED, fenceFailed.state());
        assertSame(awaitFailure, fenceFailed.failure());
        assertEquals(1, failingFence.closeCalls);
    }

    @Test
    void consumerOrFenceCloseFailureIsRetainedAsCloseFailedWithoutReplay() {
        ManualScheduler consumerScheduler = new ManualScheduler();
        FakeFence consumerFence = new FakeFence(true);
        IllegalStateException consumerFailure = new IllegalStateException("D1 consumption failure");
        int[] consumerCalls = {0};
        X7Minecraft2612SubmissionReceipt consumerFailed = X7Minecraft2612SubmissionReceipt.afterClosedPass(
                () -> consumerFence,
                consumerScheduler,
                () -> { },
                () -> {
                    consumerCalls[0]++;
                    throw consumerFailure;
                });
        consumerScheduler.dispatchNext();
        assertEquals(X7Minecraft2612SubmissionReceipt.State.CLOSE_FAILED, consumerFailed.state());
        assertSame(consumerFailure, consumerFailed.failure());
        assertEquals(1, consumerCalls[0]);
        assertEquals(1, consumerFence.closeCalls);

        ManualScheduler closeScheduler = new ManualScheduler();
        IllegalStateException fenceCloseFailure = new IllegalStateException("own fence close failure");
        FakeFence closeFailingFence = new FakeFence(true);
        closeFailingFence.closeFailure = fenceCloseFailure;
        int[] completed = {0};
        X7Minecraft2612SubmissionReceipt closeFailed = X7Minecraft2612SubmissionReceipt.afterClosedPass(
                () -> closeFailingFence,
                closeScheduler,
                () -> { },
                () -> completed[0]++);
        closeScheduler.dispatchNext();
        assertEquals(X7Minecraft2612SubmissionReceipt.State.CLOSE_FAILED, closeFailed.state());
        assertSame(fenceCloseFailure, closeFailed.failure());
        assertEquals(1, completed[0]);
        assertEquals(1, closeFailingFence.closeCalls);
    }

    @Test
    void receiptDoesNotAdoptTheShutdownFencePathOrUsePositiveTimeouts() throws IOException {
        Path sourcePath = Path.of(System.getProperty("blendlib.projectDir"))
                .resolve("src/client/java/com/liy/blendlib/fabric/client/X7Minecraft2612SubmissionReceipt.java");
        String source = Files.readString(sourcePath);

        assertTrue(source.contains("ownFence.awaitCompletion(0L)"));
        assertFalse(source.contains("ClientFinalFrameShutdownCoordinator"));
        assertFalse(source.contains("Minecraft2612OwnedFinalFenceAdapter"));
        assertFalse(source.contains("awaitCompletion(1"));
    }

    private static final class ClosedPassFenceFactory implements X7Minecraft2612SubmissionReceipt.FenceFactory {
        private final FakeFence fence;
        private boolean passClosed;
        private int creations;

        private ClosedPassFenceFactory(FakeFence fence) {
            this.fence = fence;
        }

        @Override
        public X7Minecraft2612SubmissionReceipt.OwnedFence createFence() {
            creations++;
            if (!passClosed) {
                throw new IllegalStateException("pass must close before createFence");
            }
            return fence;
        }
    }

    private static final class ManualScheduler implements X7Minecraft2612SubmissionReceipt.FencedTaskScheduler {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void queueFencedTask(Runnable task) {
            tasks.addLast(task);
        }

        private int pendingCount() {
            return tasks.size();
        }

        private Runnable takeNext() {
            return tasks.removeFirst();
        }

        private void dispatchNext() {
            takeNext().run();
        }
    }

    private static final class FakeFence implements X7Minecraft2612SubmissionReceipt.OwnedFence {
        private final ArrayDeque<Object> outcomes = new ArrayDeque<>();
        private final List<Long> awaitTimeouts = new ArrayList<>();
        private int closeCalls;
        private RuntimeException closeFailure;

        private FakeFence(Object... outcomes) {
            for (Object outcome : outcomes) {
                this.outcomes.addLast(outcome);
            }
        }

        @Override
        public boolean awaitCompletion(long timeout) {
            awaitTimeouts.add(timeout);
            Object outcome = outcomes.removeFirst();
            if (outcome instanceof RuntimeException failure) {
                throw failure;
            }
            return (boolean) outcome;
        }

        @Override
        public void close() {
            closeCalls++;
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }
}
