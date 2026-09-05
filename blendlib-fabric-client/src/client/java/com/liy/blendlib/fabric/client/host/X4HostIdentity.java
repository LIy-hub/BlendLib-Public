package com.liy.blendlib.fabric.client.host;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Objects;

/**
 * Stable client-local identity for an X4 host instance.
 *
 * <p>The scope is normally a connection/session, dimension, or first-person view epoch. The
 * local identity is never a bare Minecraft entity id, preventing cross-world reuse by design.</p>
 */
public record X4HostIdentity(BlendResourceId scope, BlendResourceId localId) {
    public X4HostIdentity {
        scope = Objects.requireNonNull(scope, "scope");
        localId = Objects.requireNonNull(localId, "localId");
    }
}
