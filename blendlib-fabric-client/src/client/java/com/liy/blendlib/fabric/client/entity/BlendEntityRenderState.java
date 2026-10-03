package com.liy.blendlib.fabric.client.entity;

import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import java.util.Objects;
import net.minecraft.client.renderer.entity.state.EntityRenderState;

/** Client render state that carries a prepared BlendLib snapshot across the Minecraft render boundary. */
public final class BlendEntityRenderState extends EntityRenderState {
    private ModelRenderSnapshot snapshot;
    private BlendEntityAttachmentComposition attachmentComposition;

    void setSnapshot(ModelRenderSnapshot snapshot) {
        var composition = BlendEntityAttachmentComposition.capture(Objects.requireNonNull(snapshot, "snapshot"));
        this.snapshot = snapshot;
        this.attachmentComposition = composition;
    }

    /** Diagnostics and flattened immutable child captures from the last extraction, or null before extraction. */
    public BlendEntityAttachmentComposition attachmentComposition() { return attachmentComposition; }

    ModelRenderSnapshot snapshotOrNull() {
        return snapshot;
    }
}
