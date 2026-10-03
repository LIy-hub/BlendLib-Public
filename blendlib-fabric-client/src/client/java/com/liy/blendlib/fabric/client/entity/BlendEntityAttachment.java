package com.liy.blendlib.fabric.client.entity;

import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import java.util.Objects;

/** A fully captured child snapshot and placement; submit needs no socket, entity or model lookup. */
public record BlendEntityAttachment(
        BlendEntitySocketPose placement, BlendEntitySocketPose offset, ModelRenderSnapshot snapshot) {
    public BlendEntityAttachment {
        placement = Objects.requireNonNull(placement, "placement");
        offset = Objects.requireNonNull(offset, "offset");
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }

    /** Attaches a prepared child, preserving the child's own root and model-unit conversion. */
    public static BlendEntityAttachment at(BlendEntitySocket socket, ModelRenderSnapshot child) {
        return at(socket, BlendEntitySocketPose.IDENTITY, child);
    }

    /** Offset is in socket-oriented blocks, before the child's own render root and unit conversion. */
    public static BlendEntityAttachment at(
            BlendEntitySocket socket, BlendEntitySocketPose offset, ModelRenderSnapshot child) {
        return new BlendEntityAttachment(Objects.requireNonNull(socket, "socket").attachmentPlacement(), offset, child);
    }
}
