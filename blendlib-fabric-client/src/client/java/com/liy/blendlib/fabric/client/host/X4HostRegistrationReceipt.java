package com.liy.blendlib.fabric.client.host;

import java.util.Objects;

/** Opaque exact-registration receipt used to prevent identity/revision ABA retirement. */
public final class X4HostRegistrationReceipt<F extends X4HostFrame> {
    private final X4HostAdapter<F> adapter;
    private final long revision;
    private final Object ownerToken;
    private final Object membershipToken;

    X4HostRegistrationReceipt(X4HostAdapter<F> adapter, long revision, Object ownerToken, Object membershipToken) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        if (revision <= 0L) {
            throw new IllegalArgumentException("registration revision must be positive");
        }
        this.revision = revision;
        this.ownerToken = Objects.requireNonNull(ownerToken, "ownerToken");
        this.membershipToken = Objects.requireNonNull(membershipToken, "membershipToken");
    }

    /** The exact adapter which was accepted by this registry operation. */
    public X4HostAdapter<F> adapter() {
        return adapter;
    }

    /** Monotonic registry-local acceptance revision, useful only for diagnostics. */
    public long revision() {
        return revision;
    }

    Object ownerToken() {
        return ownerToken;
    }

    Object membershipToken() {
        return membershipToken;
    }
}
