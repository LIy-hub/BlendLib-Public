package com.liy.blendlib.fabric.client.blockentity;

import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import java.util.Objects;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;

/**
 * Client render state carrying an extraction-captured BlendLib snapshot for one block entity.
 *
 * <p>The state deliberately retains neither a {@code BlockEntity} nor a {@code Level}. The
 * renderer submit phase can therefore consume only the immutable snapshot prepared during
 * extraction.</p>
 */
public final class BlendBlockEntityRenderState extends BlockEntityRenderState {
    private ModelRenderSnapshot snapshot;

    void setSnapshot(ModelRenderSnapshot snapshot) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }

    void clearSnapshot() {
        snapshot = null;
    }

    ModelRenderSnapshot snapshotOrNull() {
        return snapshot;
    }
}
