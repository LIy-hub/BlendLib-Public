package com.liy.blendlib.core.animation.runtime;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.model.ModelAsset;
import com.liy.blendlib.core.model.SocketTable;
import com.liy.blendlib.core.model.Transform;
import java.util.Objects;
import java.util.Optional;

/** Pure socket lookup against an already-sampled node palette. */
public final class SocketWorldTransform {
    private SocketWorldTransform() {
    }

    public static Optional<Transform> query(SocketTable sockets, NodePalette palette, BlendResourceId socketKey) {
        Objects.requireNonNull(sockets, "sockets");
        Objects.requireNonNull(palette, "palette");
        SocketTable.Socket socket = sockets.get(Objects.requireNonNull(socketKey, "socketKey"));
        return socket == null ? Optional.empty() : Optional.of(palette.worldTransform(socket.nodeIndex()));
    }

    /** Convenience overload that consumes only frozen socket data from one model asset. */
    public static Optional<Transform> query(ModelAsset asset, NodePalette palette, BlendResourceId socketKey) {
        return query(Objects.requireNonNull(asset, "asset").sockets(), palette, socketKey);
    }
}
