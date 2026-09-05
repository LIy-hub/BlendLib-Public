package com.liy.blendlib.fabric.client;

import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.Objects;

/**
 * Non-blocking receipt for one ordinary submission whose caller already closed its pass.
 *
 * <p>The receipt owns only its own fence. Scheduler fences created by {@code queueFencedTask} merely arrange a later
 * render-thread poll and never prove completion. The callback runs once only after this receipt's fence reports
 * {@code awaitCompletion(0L) == true}; no positive-timeout wait or busy loop is used.</p>
 */
final class X7Minecraft2612SubmissionReceipt {
    enum State {
        ENQUEUED,
        FENCE_PENDING,
        FENCE_SIGNALED,
        REJECTED,
        FENCE_FAILED,
        CLOSE_FAILED
    }

    @FunctionalInterface
    interface FenceFactory {
        OwnedFence createFence();
    }

    interface OwnedFence {
        boolean awaitCompletion(long timeout);

        void close();
    }

    @FunctionalInterface
    interface FencedTaskScheduler {
        void queueFencedTask(Runnable task);
    }

    @FunctionalInterface
    interface RenderThreadAssertion {
        void assertOnRenderThread();
    }

    private final FencedTaskScheduler scheduler;
    private final RenderThreadAssertion renderThreadAssertion;
    private final OwnedFence ownFence;
    private final Runnable onOwnFenceSignaled;
    private State state;
    private Throwable failure;
    private boolean consumptionAttempted;
    private boolean fenceClosed;

    static X7Minecraft2612SubmissionReceipt afterClosedPass(CommandEncoder encoder, Runnable onOwnFenceSignaled) {
        if (encoder == null) {
            return rejected(new NullPointerException("encoder"));
        }
        return afterClosedPass(
                () -> new MinecraftOwnedFence(encoder.createFence()),
                RenderSystem::queueFencedTask,
                RenderSystem::assertOnRenderThread,
                onOwnFenceSignaled);
    }

    static X7Minecraft2612SubmissionReceipt afterClosedPass(
            FenceFactory fenceFactory,
            FencedTaskScheduler scheduler,
            RenderThreadAssertion renderThreadAssertion,
            Runnable onOwnFenceSignaled) {
        if (fenceFactory == null || scheduler == null || renderThreadAssertion == null || onOwnFenceSignaled == null) {
            return rejected(new NullPointerException("ordinary submission receipt dependency"));
        }
        try {
            renderThreadAssertion.assertOnRenderThread();
            OwnedFence ownFence = Objects.requireNonNull(fenceFactory.createFence(), "ownFence");
            X7Minecraft2612SubmissionReceipt receipt = new X7Minecraft2612SubmissionReceipt(
                    scheduler, renderThreadAssertion, ownFence, onOwnFenceSignaled, State.ENQUEUED, null);
            receipt.queueContinuation();
            return receipt;
        } catch (Throwable failure) {
            return rejected(failure);
        }
    }

    private static X7Minecraft2612SubmissionReceipt rejected(Throwable failure) {
        return new X7Minecraft2612SubmissionReceipt(null, null, null, null, State.REJECTED, failure);
    }

    private X7Minecraft2612SubmissionReceipt(
            FencedTaskScheduler scheduler,
            RenderThreadAssertion renderThreadAssertion,
            OwnedFence ownFence,
            Runnable onOwnFenceSignaled,
            State state,
            Throwable failure) {
        this.scheduler = scheduler;
        this.renderThreadAssertion = renderThreadAssertion;
        this.ownFence = ownFence;
        this.onOwnFenceSignaled = onOwnFenceSignaled;
        this.state = state;
        this.failure = failure;
    }

    private synchronized void queueContinuation() {
        if (state != State.ENQUEUED && state != State.FENCE_PENDING) {
            return;
        }
        try {
            scheduler.queueFencedTask(this::onSchedulerDispatch);
            if (state == State.ENQUEUED) {
                state = State.FENCE_PENDING;
            }
        } catch (Throwable queueFailure) {
            fail(State.REJECTED, queueFailure);
            closeOwnFence();
        }
    }

    private synchronized void onSchedulerDispatch() {
        if (state != State.ENQUEUED && state != State.FENCE_PENDING) {
            return;
        }
        state = State.FENCE_PENDING;
        try {
            renderThreadAssertion.assertOnRenderThread();
            if (!ownFence.awaitCompletion(0L)) {
                queueContinuation();
                return;
            }
            state = State.FENCE_SIGNALED;
            consumeAndClose();
        } catch (Throwable awaitFailure) {
            fail(State.FENCE_FAILED, awaitFailure);
            closeOwnFence();
        }
    }

    private void consumeAndClose() {
        if (!consumptionAttempted) {
            consumptionAttempted = true;
            try {
                onOwnFenceSignaled.run();
            } catch (Throwable closeFailure) {
                fail(State.CLOSE_FAILED, closeFailure);
            }
        }
        closeOwnFence();
    }

    private void closeOwnFence() {
        if (fenceClosed || ownFence == null) {
            return;
        }
        fenceClosed = true;
        try {
            ownFence.close();
        } catch (Throwable closeFailure) {
            if (failure == null) {
                fail(State.CLOSE_FAILED, closeFailure);
            } else {
                failure.addSuppressed(closeFailure);
            }
        }
    }

    private void fail(State failureState, Throwable failure) {
        state = failureState;
        if (this.failure == null) {
            this.failure = failure;
        } else if (this.failure != failure) {
            this.failure.addSuppressed(failure);
        }
    }

    synchronized State state() {
        return state;
    }

    synchronized Throwable failure() {
        return failure;
    }

    synchronized boolean fenceClosed() {
        return fenceClosed;
    }

    private record MinecraftOwnedFence(GpuFence delegate) implements OwnedFence {
        private MinecraftOwnedFence {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public boolean awaitCompletion(long timeout) {
            return delegate.awaitCompletion(timeout);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
