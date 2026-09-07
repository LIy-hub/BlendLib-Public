package com.liy.blendlib.fabric.client.entity;

import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import java.util.Objects;

/** Snapshot-only state for 1.21.1, before vanilla introduced EntityRenderState. */
public final class BlendEntityRenderState {
    private ModelRenderSnapshot snapshot;
    void setSnapshot(ModelRenderSnapshot snapshot) { this.snapshot = Objects.requireNonNull(snapshot, "snapshot"); }
    ModelRenderSnapshot snapshotOrNull() { return snapshot; }
}
