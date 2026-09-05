package com.liy.blendlib.fabric.client.host;

import com.liy.blendlib.fabric.client.render.RenderSubmissionContext;
import java.util.concurrent.CompletionStage;

/**
 * Version-scoped X4 client-host adapter lifecycle.
 *
 * <p>Configuration is completed by a typed builder. {@link #freeze()} prohibits late mutation;
 * {@link #prepare(X4HostFrame)} performs any allowed lookup/extraction before rendering; and
 * {@link #submit(X4PreparedSnapshot, RenderSubmissionContext)} consumes only the pinned immutable
 * snapshot through the existing renderer facade. This is a client-facing experimental integration
 * seam: a platform lifecycle owner, not an ordinary consumer or common/server entrypoint, owns
 * construction, resource-generation session binding, retirement, and shutdown.</p>
 */
public interface X4HostAdapter<F extends X4HostFrame> extends AutoCloseable {
    /** Returns the immutable configuration produced during the configure phase. */
    X4HostSpec<F> configure();

    /** Current lifecycle state. */
    X4HostLifecycleState state();

    /** Immutable local ownership counts for active snapshots and in-flight submits. */
    X4HostLeaseDiagnostics leaseDiagnostics();

    /**
     * Completes after a terminal epoch seals and every real generation pin and retained membership
     * source has drained.
     */
    CompletionStage<X4HostLifecycleState> drainCompletion();

    /** Freezes this adapter before it may bind a resource generation. */
    void freeze();

    /** Resolves or validates an already extracted snapshot before the submit hot path. */
    X4PreparedSnapshot prepare(F frame);

    /** Semantic alias for render integrations whose pre-submit stage is called extraction. */
    default X4PreparedSnapshot extract(F frame) {
        return prepare(frame);
    }

    /** Submits one still-open lease without a registry lookup, parser, or resource access. */
    void submit(X4PreparedSnapshot prepared, RenderSubmissionContext context);

    /**
     * Requests retirement through the terminal epoch; opening it rejects new prepare and submit
     * admission.
     *
     * <p>Every already-open snapshot remains a future provider-release source and must close;
     * only submit holds admitted before the epoch may finish. An ordinary non-reentrant caller
     * waits for those sources and replays the sealed terminal outcome. Reentry by the exact
     * callback or contribution owner only coalesces into its outer operation and does not wait on
     * itself.</p>
     */
    void retire();

    /**
     * Requests shutdown through the terminal epoch, monotonically upgrading a retirement request;
     * opening the epoch rejects new prepare and submit admission.
     *
     * <p>Every already-open snapshot remains a future provider-release source and must close;
     * only submit holds admitted before the epoch may finish. An ordinary non-reentrant caller
     * waits for those sources and replays the sealed terminal outcome. Reentry by the exact
     * callback or contribution owner only coalesces into its outer operation and does not wait on
     * itself.</p>
     */
    @Override
    void close();
}
