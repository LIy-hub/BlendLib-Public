package com.liy.blendlib.fabric.v262.runtime;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Objects;

/**
 * Exact opaque receipt returned by one Minecraft 26.2 Fabric runtime installation.
 *
 * <p><strong>Platform target:</strong> Fabric on Minecraft 26.2. The receipt is intentionally
 * unforgeable outside this package: shutdown must present the same object identity and epoch that
 * {@link Fabric262ClientRuntime#start()} returned, preventing an integration owner from closing a
 * different adapter installation accidentally.</p>
 */
public final class Fabric262InstallationReceipt {
    private final BlendResourceId adapterId;
    private final long epoch;
    private final Object ownershipToken;

    Fabric262InstallationReceipt(BlendResourceId adapterId, long epoch, Object ownershipToken) {
        this.adapterId = Objects.requireNonNull(adapterId, "adapterId");
        if (epoch < 1L) {
            throw new IllegalArgumentException("epoch must be positive");
        }
        this.epoch = epoch;
        this.ownershipToken = Objects.requireNonNull(ownershipToken, "ownershipToken");
    }

    /**
     * Returns the independent version-specific adapter identity.
     *
     * @return canonical Fabric 26.2 adapter identity
     */
    public BlendResourceId adapterId() {
        return adapterId;
    }

    /**
     * Returns the monotonic process-local installation epoch for diagnostics.
     *
     * @return positive installation epoch
     */
    public long epoch() {
        return epoch;
    }

    boolean matches(Fabric262InstallationReceipt other) {
        return other != null && adapterId.equals(other.adapterId)
                && epoch == other.epoch && ownershipToken == other.ownershipToken;
    }
}
