package com.liy.blendlib.fabric.client.entity;

import java.util.List;
import net.minecraft.world.entity.Entity;

/** Prepares immutable child model snapshots during extraction, after final-pose socket capture. */
@FunctionalInterface
public interface BlendEntityAttachmentProvider<E extends Entity> {
    /** Return an empty list for no attachments; unknown sockets should be handled with {@link BlendEntitySockets#socket}. */
    List<BlendEntityAttachment> attachments(E entity, BlendEntitySnapshotRequest request, BlendEntitySockets sockets);
}
