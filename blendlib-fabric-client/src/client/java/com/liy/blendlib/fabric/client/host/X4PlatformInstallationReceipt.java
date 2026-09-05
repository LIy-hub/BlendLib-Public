package com.liy.blendlib.fabric.client.host;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Objects;

/** Opaque exact-installation receipt for the manually installed X4 platform bridge. */
public final class X4PlatformInstallationReceipt {
    private final Minecraft2612X4PlatformAdapter adapter;
    private final BlendResourceId providerId;
    private final long revision;
    private final Object ownerToken;

    X4PlatformInstallationReceipt(
            Minecraft2612X4PlatformAdapter adapter,
            BlendResourceId providerId,
            long revision,
            Object ownerToken) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.providerId = Objects.requireNonNull(providerId, "providerId");
        if (revision <= 0L) {
            throw new IllegalArgumentException("installation revision must be positive");
        }
        this.revision = revision;
        this.ownerToken = Objects.requireNonNull(ownerToken, "ownerToken");
    }

    /** Unique provider identity for the exact bridge instance which was installed. */
    public BlendResourceId providerId() {
        return providerId;
    }

    /** Monotonic per-instance installation revision, for diagnostics only. */
    public long revision() {
        return revision;
    }

    Minecraft2612X4PlatformAdapter adapter() {
        return adapter;
    }

    Object ownerToken() {
        return ownerToken;
    }
}
