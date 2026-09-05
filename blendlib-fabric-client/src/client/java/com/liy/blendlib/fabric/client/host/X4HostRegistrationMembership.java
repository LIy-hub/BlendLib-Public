package com.liy.blendlib.fabric.client.host;

/**
 * Registry-owned exact membership bound to one internal X4 lifecycle reservation.
 *
 * <p>The only operation is idempotent revocation. Implementations must call it without holding an
 * adapter lifecycle monitor; the registry never calls an adapter while holding its own monitor.
 * This package-private bridge keeps the reservation mechanics internal while allowing the public
 * managed migration handle to participate safely.</p>
 */
interface X4HostRegistrationMembership {
    /** Monotonic registry-local revision carried only for internal diagnostics and exact matching. */
    long revision();

    /** Atomically removes this exact membership. Repeated calls are harmless. */
    void revoke();
}
