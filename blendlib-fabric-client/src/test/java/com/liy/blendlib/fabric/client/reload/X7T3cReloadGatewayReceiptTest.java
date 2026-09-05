package com.liy.blendlib.fabric.client.reload;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class X7T3cReloadGatewayReceiptTest {
    @Test
    void zeroPollRetainsPendingThenRemovesOnlyVerifiedStaticFenceCompletion() {
        long renderThreadId = Thread.currentThread().threadId();
        X7T3cReloadGateway gateway = gateway(renderThreadId);
        X7DeferredSubmissionCompletionReceipt completion = new X7DeferredSubmissionCompletionReceipt(
                new X7DeferredFrameQueue(renderThreadId));
        MutableFence fence = new MutableFence();
        CloseProbe transientClose = new CloseProbe();
        int slot = gateway.reserveStaticFenceSlot();
        gateway.retainStaticFence(slot, StaticDirectFenceReceipt.pending(fence, transientClose, List.of(completion)));

        gateway.pollRetainedStaticFencesForTest(() -> { });

        assertEquals(1, gateway.pendingStaticFenceCount());
        assertEquals(X7DeferredSubmissionCompletionReceipt.State.COMMAND_RECORDED, completion.state());
        assertEquals(0, fence.closeCalls);
        assertEquals(0, transientClose.closeCalls);

        fence.complete = true;
        gateway.pollRetainedStaticFencesForTest(() -> { });

        assertEquals(0, gateway.pendingStaticFenceCount());
        assertEquals(X7DeferredSubmissionCompletionReceipt.State.COMPLETED, completion.state());
        assertEquals(1, fence.closeCalls);
        assertEquals(1, transientClose.closeCalls);
    }

    @Test
    void terminalStaticFenceSurvivesReloadCutoffWithoutReleasingItsCompletion() {
        long renderThreadId = Thread.currentThread().threadId();
        X7T3cReloadGateway gateway = gateway(renderThreadId);
        X7DeferredSubmissionCompletionReceipt completion = new X7DeferredSubmissionCompletionReceipt(
                new X7DeferredFrameQueue(renderThreadId));
        MutableFence fence = new MutableFence();
        fence.failure = new IllegalStateException("future fence unavailable");
        int slot = gateway.reserveStaticFenceSlot();
        gateway.retainStaticFence(slot, StaticDirectFenceReceipt.pending(fence, () -> { }, List.of(completion)));

        gateway.pollRetainedStaticFencesForTest(() -> { });
        gateway.closeAdmissionAndCancelNoCommand();

        assertEquals(1, gateway.pendingStaticFenceCount());
        assertEquals(StaticDirectFenceReceipt.State.TERMINAL_NON_CLOSE, gateway.staticFenceStateForTest(slot));
        assertEquals(X7DeferredSubmissionCompletionReceipt.State.TERMINAL_NONCLOSE, completion.state());
    }

    private static X7T3cReloadGateway gateway(long renderThreadId) {
        return X7T3cReloadGateway.forTest(renderThreadId, 1, (batch, scope, commands) -> {
            throw new AssertionError("receipt-owner test does not issue a native command");
        });
    }

    private static final class MutableFence implements StaticDirectFenceReceipt.Fence {
        private boolean complete;
        private RuntimeException failure;
        private int closeCalls;

        @Override
        public boolean awaitCompletionZero() {
            if (failure != null) {
                throw failure;
            }
            return complete;
        }

        @Override
        public void close() {
            closeCalls++;
        }
    }

    private static final class CloseProbe implements StaticDirectFenceReceipt.TransientClose {
        private int closeCalls;

        @Override
        public void close() {
            closeCalls++;
        }
    }
}
