package com.liy.blendlib.examples.runnable;

import com.liy.blendlib.api.BlendInstanceKey;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.function.Consumer;

/** One child clock per actual loaded actor object, never a reusable numeric entity ID. */
public final class ExampleAttachmentOwners {
    private final IdentityHashMap<Object, BlendInstanceKey.Ephemeral> owners = new IdentityHashMap<>();
    private String session;
    private long sequence;

    public BlendInstanceKey.Ephemeral key(Object owner, String session, Consumer<BlendInstanceKey> retire) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(session, "session");
        if (!session.equals(this.session)) {
            clear(retire);
            this.session = session;
        }
        return owners.computeIfAbsent(owner, ignored -> BlendInstanceKey.ephemeral(
                session, "blendlib-example-ornament-" + (++sequence)));
    }

    public void remove(Object owner, Consumer<BlendInstanceKey> retire) {
        var key = owners.remove(owner);
        if (key != null) retire.accept(key);
    }

    public void clear(Consumer<BlendInstanceKey> retire) {
        owners.values().forEach(retire);
        owners.clear();
        session = null;
    }
}
