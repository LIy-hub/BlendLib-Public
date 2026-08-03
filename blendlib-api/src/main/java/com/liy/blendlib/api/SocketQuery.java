package com.liy.blendlib.api;

import java.util.Objects;

/**
 * Immutable semantic request for a named socket on one pinned model instance.
 *
 * <p>Resolving a query is an extraction-stage concern. This request deliberately contains no
 * transform, bone matrix, platform identifier, or renderer object.</p>
 *
 * @param modelInstance pinned instance/model/generation identity
 * @param socketId canonical semantic socket identity
 */
public record SocketQuery(ModelInstance modelInstance, BlendResourceId socketId) {

    /** Validates the purely semantic socket target. */
    public SocketQuery {
        modelInstance = Objects.requireNonNull(modelInstance, "modelInstance");
        socketId = Objects.requireNonNull(socketId, "socketId");
    }

    /**
     * Creates a socket query from a pinned instance and a canonical socket id.
     *
     * @param modelInstance pinned model instance
     * @param socketId canonical socket identity
     * @return immutable socket query
     */
    public static SocketQuery of(ModelInstance modelInstance, BlendResourceId socketId) {
        return new SocketQuery(modelInstance, socketId);
    }
}
