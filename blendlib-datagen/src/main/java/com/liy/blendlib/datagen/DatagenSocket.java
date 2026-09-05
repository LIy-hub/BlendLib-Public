package com.liy.blendlib.datagen;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Objects;

/**
 * Immutable strict descriptor socket declaration.
 *
 * <p><strong>Stable boundary:</strong> the node path addresses validated GLB scene hierarchy data.
 * It does not retain a model node object, world position, or platform host handle.</p>
 *
 * @param socketId canonical socket identity
 * @param nodePath non-blank slash-separated GLB node path
 */
public record DatagenSocket(BlendResourceId socketId, String nodePath) {
    /**
     * Validates a strict descriptor socket declaration.
     */
    public DatagenSocket {
        socketId = Objects.requireNonNull(socketId, "socketId");
        nodePath = Objects.requireNonNull(nodePath, "nodePath");
        if (nodePath.isBlank() || nodePath.startsWith("/") || nodePath.endsWith("/") || nodePath.contains("//")) {
            throw new IllegalArgumentException("nodePath must be a non-blank normalized relative node path");
        }
    }
}
