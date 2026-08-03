package com.liy.blendlib.fabric.client.animation.sync;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Objects;

/** Typed client-side target identity before a command is bound to the active connection session. */
public sealed interface ClientAnimationTarget permits ClientAnimationTarget.EntityTarget, ClientAnimationTarget.BlockEntityTarget {
    /** Network entity id, deliberately not yet a full {@code BlendInstanceKey.Entity}. */
    record EntityTarget(int entityId) implements ClientAnimationTarget {
        public EntityTarget {
            if (entityId < 0) {
                throw new IllegalArgumentException("entityId must be non-negative");
            }
        }
    }

    /** Current-level dimension plus packed block position; a naked block position is never retained. */
    record BlockEntityTarget(BlendResourceId dimension, long packedBlockPos) implements ClientAnimationTarget {
        public BlockEntityTarget {
            dimension = Objects.requireNonNull(dimension, "dimension");
        }
    }
}
