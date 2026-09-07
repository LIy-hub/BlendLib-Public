package com.liy.blendlib.fabric.client.blockentity;

import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import java.util.Objects;

/** Immutable-snapshot holder retained across the legacy extraction/submission bridge. */
public final class BlendBlockEntityRenderState {
    public int lightCoords;
    private ModelRenderSnapshot snapshot;
    void setSnapshot(ModelRenderSnapshot snapshot) { this.snapshot = Objects.requireNonNull(snapshot); }
    void clearSnapshot() { snapshot = null; }
    ModelRenderSnapshot snapshotOrNull() { return snapshot; }
}
