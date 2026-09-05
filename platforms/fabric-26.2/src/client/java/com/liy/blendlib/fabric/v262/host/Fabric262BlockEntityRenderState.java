package com.liy.blendlib.fabric.v262.host;

import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;

/** Client block-entity render state carrying one exact dispatcher-owned pinned snapshot. */
final class Fabric262BlockEntityRenderState extends BlockEntityRenderState {
    private Fabric262DispatchSnapshot snapshot;

    void replaceSnapshot(Fabric262DispatchSnapshot next) {
        try {
            clearSnapshot();
        } catch (RuntimeException exception) {
            if (next != null) {
                try {
                    next.close();
                } catch (RuntimeException closeFailure) {
                    exception.addSuppressed(closeFailure);
                }
            }
            throw exception;
        }
        snapshot = next;
    }

    Fabric262DispatchSnapshot snapshotForSubmit() {
        return snapshot;
    }

    void releaseSubmittedSnapshot(Fabric262DispatchSnapshot expected) {
        if (snapshot != expected) {
            if (expected != null) {
                expected.close();
            }
            return;
        }
        if (expected != null) {
            expected.close();
            snapshot = null;
        }
    }

    void clearSnapshot() {
        Fabric262DispatchSnapshot previous = snapshot;
        if (previous != null) {
            previous.close();
            // Do not lose retry ownership before the generation release reports success.
            snapshot = null;
        }
    }
}
