package com.liy.blendlib.core.model;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable logical socket-key to unique model-node mapping. */
public final class SocketTable {
    private final Map<BlendResourceId, Socket> sockets;

    public SocketTable(Map<BlendResourceId, Socket> sockets) {
        Objects.requireNonNull(sockets, "sockets");
        this.sockets = Collections.unmodifiableMap(new LinkedHashMap<>(sockets));
    }

    public Map<BlendResourceId, Socket> entries() {
        return sockets;
    }

    public Socket get(BlendResourceId key) {
        return sockets.get(key);
    }

    /** One resolved socket path and node index. */
    public record Socket(int nodeIndex, String nodePath) {
        public Socket {
            if (nodeIndex < 0) {
                throw new IllegalArgumentException("Socket node index must be non-negative");
            }
            nodePath = Objects.requireNonNull(nodePath, "nodePath");
            if (nodePath.isBlank()) {
                throw new IllegalArgumentException("Socket node path must not be blank");
            }
        }
    }
}
