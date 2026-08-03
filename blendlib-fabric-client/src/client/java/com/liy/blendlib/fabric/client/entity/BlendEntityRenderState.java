package com.liy.blendlib.fabric.client.entity;

import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import java.util.Objects;
import net.minecraft.client.renderer.entity.state.EntityRenderState;

/** Client render state that carries a prepared BlendLib snapshot across the Minecraft render boundary. */
public final class BlendEntityRenderState extends EntityRenderState {
    private ModelRenderSnapshot snapshot;

    void setSnapshot(ModelRenderSnapshot snapshot) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }

    ModelRenderSnapshot snapshotOrNull() {
        return snapshot;
    }
}
