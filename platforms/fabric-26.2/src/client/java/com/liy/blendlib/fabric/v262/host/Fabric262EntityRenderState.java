package com.liy.blendlib.fabric.v262.host;

import net.minecraft.client.renderer.entity.state.EntityRenderState;

/** Client render state carrying exactly one dispatcher-owned pinned snapshot. */
final class Fabric262EntityRenderState extends EntityRenderState {
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
            // Preserve the exact wrapper on a failed close so the next extraction can retry it;
            // the dispatcher also retains the raw generation lease until release succeeds.
            snapshot = null;
        }
    }
}
