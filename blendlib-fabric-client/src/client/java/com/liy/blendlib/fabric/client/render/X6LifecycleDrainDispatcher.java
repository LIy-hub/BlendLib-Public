package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.spi.experimental.ExperimentalBlendLibSpi;

/**
 * Host-owned admission and lifecycle-owner delivery for successful X6-plan drain work.
 *
 * <p>Before X6 pins a provider lease, {@link #register(Runnable)} must retain the supplied
 * prebuilt runnable in the real host lifecycle owner's bounded registration set. It must not run
 * that runnable before the returned {@link Admission} is asked to drain it. A rejecting host
 * throws from {@code register}; X6 then fails before pinning rather than returning an ownerless
 * plan. Hosts keep every admitted runnable reachable through reload and shutdown, including after
 * a later request failure, until it is cancelled or reaches its terminal owner-side completion.
 * If an owner starts the bridge before {@code requestDrain()} returns, an initial terminal result
 * is not permission to discard that registration: a late request failure can select a different
 * final owner outcome. The host must retain the bridge until request acknowledgement is final and,
 * after such a failure, make it available for defensive owner-side observation/replay.</p>
 */
@ExperimentalBlendLibSpi
@FunctionalInterface
public interface X6LifecycleDrainDispatcher {
    /**
     * Retains one prebuilt bridge before its provider lease is pinned.
     *
     * <p>This method is an admission handshake, not a close signal. It must be nonblocking and
     * must fail before retaining nothing when capacity or lifecycle state cannot support the
     * bridge. Implementations must never invoke {@code prebuiltDrain} inline or before the later
     * {@link Admission#requestDrain()} call.</p>
     *
     * @throws RuntimeException if the host cannot admit the bridge before pinning
     */
    Admission register(Runnable prebuiltDrain);

    /** One registered bridge's host-owned request and pre-publication cancellation capability. */
    interface Admission {
        /**
         * Nonblocking asynchronous signal to run the retained bridge on the lifecycle owner.
         *
         * <p>The host must not invoke the bridge inline on this caller. If scheduling reports a
         * failure after admission, the host still retains the bridge for its lifecycle-owner
         * emergency-drain path; that remains true when the bridge completed early on another
         * owner thread, because X6 records the request failure without falling back to caller-inline
         * provider close and a later owner replay observes the final selected outcome.</p>
         */
        void requestDrain();

        /**
         * Removes a bridge whose successful-plan publication did not complete.
         *
         * <p>Cancellation never closes the provider lease. X6 calls it before releasing a
         * post-pin failed-publication lease through the existing failed-publication path.</p>
         */
        void cancel();
    }
}
