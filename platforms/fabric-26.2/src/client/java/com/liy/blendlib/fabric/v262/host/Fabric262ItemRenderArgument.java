package com.liy.blendlib.fabric.v262.host;

import java.util.Objects;

/**
 * Item extraction handoff with one exact dispatcher-owned snapshot and immutable frozen pose.
 *
 * <p>The argument never retains an {@code ItemStack}, world, renderer, or controller. If its
 * release fails, it keeps the exact snapshot reference until a later submit/replacement retry;
 * the dispatcher retains the raw generation pin independently for terminal draining.</p>
 */
final class Fabric262ItemRenderArgument {
    private final Fabric262ItemModelBindings.ItemBinding binding;
    private Fabric262DispatchSnapshot snapshot;

    Fabric262ItemRenderArgument(
            Fabric262ItemModelBindings.ItemBinding binding,
            Fabric262DispatchSnapshot snapshot) {
        this.binding = Objects.requireNonNull(binding, "binding");
        this.snapshot = snapshot;
    }

    Fabric262ItemModelBindings.ItemBinding binding() {
        return binding;
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
}
