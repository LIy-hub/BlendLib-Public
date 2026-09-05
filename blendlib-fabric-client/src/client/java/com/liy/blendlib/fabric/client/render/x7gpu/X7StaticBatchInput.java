package com.liy.blendlib.fabric.client.render.x7gpu;

import java.util.Objects;

/** One already-resolved static instance; no model lookup belongs in submit planning. */
record X7StaticBatchInput(X7StaticBatchKey key, String instanceId) {
    X7StaticBatchInput {
        key = Objects.requireNonNull(key, "key");
        instanceId = Objects.requireNonNull(instanceId, "instanceId");
        if (instanceId.isBlank()) {
            throw new IllegalArgumentException("X7 static batch instanceId must not be blank");
        }
    }
}
