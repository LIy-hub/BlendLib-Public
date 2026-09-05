package com.liy.blendlib.fabric.client.reload;

import com.mojang.blaze3d.systems.RenderSystem;
import java.util.List;
import java.util.Objects;

/**
 * Non-blocking retention for a command-recorded direct-static draw.
 *
 * <p>Only a zero-timeout positive fence result releases the D1 completion child. A false poll or fence exception
 * intentionally keeps both the child and per-command transient alive; a later T3c owner is responsible for
 * scheduling polls and terminal diagnostics.</p>
 */
final class StaticDirectFenceReceipt {
    @FunctionalInterface
    interface RenderThreadAssertion {
        void assertOnRenderThread();
    }

    interface Fence {
        boolean awaitCompletionZero();

        void close();
    }

    interface TransientClose {
        void close();
    }

    enum State {
        PENDING,
        TERMINAL_NON_CLOSE,
        COMPLETED
    }

    enum PollResult {
        PENDING,
        TERMINAL_NON_CLOSE,
        COMPLETED
    }

    private final Fence fence;
    private final TransientClose transientClose;
    private final List<X7DeferredSubmissionCompletionReceipt> completions;
    private State state;
    private Throwable terminalFailure;

    private StaticDirectFenceReceipt(
            Fence fence,
            TransientClose transientClose,
            List<X7DeferredSubmissionCompletionReceipt> completions,
            State state,
            Throwable terminalFailure) {
        this.fence = fence;
        this.transientClose = Objects.requireNonNull(transientClose, "transientClose");
        this.completions = checkedCompletions(completions);
        this.state = Objects.requireNonNull(state, "state");
        this.terminalFailure = terminalFailure;
    }

    static StaticDirectFenceReceipt pending(
            Fence fence, TransientClose transientClose, List<X7DeferredSubmissionCompletionReceipt> completions) {
        return new StaticDirectFenceReceipt(
                Objects.requireNonNull(fence, "fence"), transientClose, completions, State.PENDING, null);
    }

    static StaticDirectFenceReceipt terminal(
            TransientClose transientClose,
            List<X7DeferredSubmissionCompletionReceipt> completions,
            Throwable failure) {
        Throwable checkedFailure = Objects.requireNonNull(failure, "failure");
        List<X7DeferredSubmissionCompletionReceipt> checkedCompletions = checkedCompletions(completions);
        retainTerminalCompletions(checkedCompletions, checkedFailure);
        return new StaticDirectFenceReceipt(null, transientClose, checkedCompletions, State.TERMINAL_NON_CLOSE, checkedFailure);
    }

    synchronized PollResult pollOnRenderThread() {
        return poll(RenderSystem::assertOnRenderThread);
    }

    synchronized PollResult poll(RenderThreadAssertion renderThreadAssertion) {
        Objects.requireNonNull(renderThreadAssertion, "renderThreadAssertion").assertOnRenderThread();
        if (state == State.COMPLETED) {
            return PollResult.COMPLETED;
        }
        if (state == State.TERMINAL_NON_CLOSE) {
            return PollResult.TERMINAL_NON_CLOSE;
        }
        final boolean complete;
        try {
            complete = fence.awaitCompletionZero();
        } catch (Throwable failure) {
            retainTerminal(failure);
            return PollResult.TERMINAL_NON_CLOSE;
        }
        if (!complete) {
            return PollResult.PENDING;
        }
        Throwable closeFailure = null;
        try {
            fence.close();
        } catch (Throwable failure) {
            closeFailure = failure;
        }
        try {
            transientClose.close();
        } catch (Throwable failure) {
            if (closeFailure == null) {
                closeFailure = failure;
            } else if (closeFailure != failure) {
                closeFailure.addSuppressed(failure);
            }
        }
        for (X7DeferredSubmissionCompletionReceipt completion : completions) {
            try {
                completion.completeAfterVerifiedFence();
            } catch (Throwable failure) {
                if (closeFailure == null) {
                    closeFailure = failure;
                } else if (closeFailure != failure) {
                    closeFailure.addSuppressed(failure);
                }
            }
        }
        state = State.COMPLETED;
        if (closeFailure != null) {
            terminalFailure = closeFailure;
        }
        return PollResult.COMPLETED;
    }

    synchronized State state() {
        return state;
    }

    synchronized Throwable terminalFailureOrNull() {
        return terminalFailure;
    }

    /** Marks a preallocated receipt terminal without releasing a child or a transient that a command may reference. */
    synchronized void retainTerminalNonClose(Throwable failure) {
        retainTerminal(Objects.requireNonNull(failure, "failure"));
    }

    private void retainTerminal(Throwable failure) {
        retainTerminalCompletions(completions, failure);
        state = State.TERMINAL_NON_CLOSE;
        if (terminalFailure == null) {
            terminalFailure = failure;
        } else if (terminalFailure != failure) {
            terminalFailure.addSuppressed(failure);
        }
    }

    private static List<X7DeferredSubmissionCompletionReceipt> checkedCompletions(
            List<X7DeferredSubmissionCompletionReceipt> completions) {
        List<X7DeferredSubmissionCompletionReceipt> checked = List.copyOf(Objects.requireNonNull(completions, "completions"));
        if (checked.isEmpty()) {
            throw new IllegalArgumentException("A command-recorded fence receipt must retain at least one queue child");
        }
        return checked;
    }

    static void retainTerminalCompletions(List<X7DeferredSubmissionCompletionReceipt> completions, Throwable failure) {
        for (X7DeferredSubmissionCompletionReceipt completion : completions) {
            completion.retainTerminalNonClose(failure);
        }
    }
}
