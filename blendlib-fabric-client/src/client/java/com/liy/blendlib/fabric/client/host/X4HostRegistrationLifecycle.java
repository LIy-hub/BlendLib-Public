package com.liy.blendlib.fabric.client.host;

/**
 * Package-private registry seam implemented by factory-owned X4 lifecycle implementations.
 *
 * <p>The public {@link X4HostAdapter} surface deliberately does not expose registration locking.
 * A registry must instead obtain this opaque reservation from the same monitor that owns the
 * lifecycle state, so an independently observed {@link X4HostLifecycleState#FROZEN} value cannot
 * be reused after close, retire, or prepare crosses the lifecycle boundary. Public external
 * adapters instead transfer lifecycle ownership to {@link X4ManagedHostAdapter}; the registry
 * reaches that handle through a separate package-private bridge, not by exposing this marker or a
 * reservation type in public API.</p>
 */
interface X4HostRegistrationLifecycle<F extends X4HostFrame> {
    /** Atomically freezes a configuring adapter and reserves its frozen lifecycle for one registration. */
    X4HostRegistrationReservation<F> freezeAndReserveRegistration();
}
