package com.liy.blendlib.api;

import java.util.Objects;

/**
 * Immutable semantic binding of one typed instance to one model in one published resource generation.
 *
 * <p>This type does not contain asset bytes, animation controller state, a pose, a bone matrix, or a
 * rendering handle. It is the public boundary between resource identity and per-instance identity.</p>
 *
 * @param instanceKey typed, session-safe instance identity
 * @param modelKey semantic model identity
 * @param resourceGeneration non-negative published resource generation
 */
public record ModelInstance(
        BlendInstanceKey instanceKey,
        BlendModelKey modelKey,
        long resourceGeneration) {

    /** Validates the resource/instance boundary and its generation number. */
    public ModelInstance {
        instanceKey = Objects.requireNonNull(instanceKey, "instanceKey");
        modelKey = Objects.requireNonNull(modelKey, "modelKey");
        if (resourceGeneration < 0L) {
            throw new IllegalArgumentException("resourceGeneration must be non-negative");
        }
    }
}
