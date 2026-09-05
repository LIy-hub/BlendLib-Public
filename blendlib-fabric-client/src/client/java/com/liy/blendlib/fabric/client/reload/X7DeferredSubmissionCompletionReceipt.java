package com.liy.blendlib.fabric.client.reload;

import java.util.Objects;

/**
 * Queue-private retention for command-recorded work.
 *
 * <p>A false/throwing future fence poll must select {@link #retainTerminalNonClose(Throwable)}, not {@link
 * #completeAfterVerifiedFence()}.  The child is released only after verified completion (or a separately verified
 * terminal disposition); ordinary queue shutdown intentionally leaves this receipt retained.</p>
 */
final class X7DeferredSubmissionCompletionReceipt {
    enum State {
        COMMAND_RECORDED,
        TERMINAL_NONCLOSE,
        COMPLETED
    }

    private final X7DeferredFrameQueue owner;
    private X7DeferredSubmissionBridge.QueueChild child;
    private State state = State.COMMAND_RECORDED;
    private Throwable terminalFailure;

    X7DeferredSubmissionCompletionReceipt(X7DeferredFrameQueue owner) {
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    /** Called only after this receipt's completion-map entry exists and the queue child has detached. */
    void acceptQueueChild(X7DeferredSubmissionBridge.QueueChild ownedChild) {
        child = ownedChild;
    }

    synchronized void retainTerminalNonClose(Throwable failure) {
        if (state == State.COMPLETED) {
            return;
        }
        state = State.TERMINAL_NONCLOSE;
        if (terminalFailure == null && failure != null) {
            terminalFailure = failure;
        } else if (terminalFailure != null && failure != null && terminalFailure != failure) {
            terminalFailure.addSuppressed(failure);
        }
    }

    void completeAfterVerifiedFence() {
        X7DeferredSubmissionBridge.QueueChild owned;
        synchronized (this) {
            if (state == State.COMPLETED) {
                return;
            }
            owned = child;
            child = null;
            state = State.COMPLETED;
        }
        try {
            if (owned != null) {
                owned.close();
            }
        } finally {
            owner.onCompletionReleased(this);
        }
    }

    synchronized State state() {
        return state;
    }

    synchronized Throwable terminalFailure() {
        return terminalFailure;
    }
}
