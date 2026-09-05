package com.liy.blendlib.fabric.client.host;

/** Opaque package-private lifecycle reservation owned by exactly one registry registration operation. */
interface X4HostRegistrationReservation<F extends X4HostFrame> {
    /** Immutable specification protected by this reservation. */
    X4HostSpec<F> specification();

    /**
     * Commits the reservation only while its owning lifecycle remains frozen and binds the exact
     * registry membership that must be revoked before a terminal lifecycle transition.
     */
    void commit(X4HostRegistrationMembership membership);

    /** Releases an uncommitted reservation after a failed registry transaction. */
    void abort();
}
