package com.liy.blendlib.fabric.client.blockentity;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.fabric.client.api.BlendLibClientServices;
import com.liy.blendlib.fabric.client.api.BlendRenderer;
import java.util.Objects;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Mutable-at-configuration-time builder for the public 26.1.2 block-entity adapter. */
public final class BlendBlockEntityRendererBuilder<T extends BlockEntity> {
    private final BlockEntityRendererProvider.Context context;
    private final BlendModelKey modelKey;
    private final BlendRenderer renderer;
    private BlendBlockEntitySnapshotFactory<? super T> snapshotFactory;

    BlendBlockEntityRendererBuilder(
            BlockEntityRendererProvider.Context context, BlendModelKey modelKey, BlendRenderer renderer) {
        this.context = Objects.requireNonNull(context, "context");
        this.modelKey = Objects.requireNonNull(modelKey, "modelKey");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
    }

    /** Supplies extraction-only snapshot construction for this block-entity type. */
    public BlendBlockEntityRendererBuilder<T> snapshotFactory(BlendBlockEntitySnapshotFactory<? super T> snapshotFactory) {
        if (this.snapshotFactory != null) {
            throw new IllegalStateException("Choose exactly one block-entity snapshot construction path");
        }
        this.snapshotFactory = Objects.requireNonNull(snapshotFactory, "snapshotFactory");
        return this;
    }

    /**
     * Configures the public static/rigid rest-pose path.
     *
     * <p>Model resolution happens only during extraction. The renderer submit phase later receives
     * the already bound immutable snapshot.</p>
     */
    public BlendBlockEntityRendererBuilder<T> staticRestPose() {
        if (snapshotFactory != null) {
            throw new IllegalStateException("Choose either staticRestPose or a custom snapshotFactory, not both");
        }
        snapshotFactory = (blockEntity, request) -> StaticRestPoseBlockEntitySnapshotFactory.create(
                BlendLibClientServices.models(), request);
        return this;
    }

    /**
     * Configures a skinned block-entity path driven by the latest P6 semantic synchronization
     * state for the typed dimension-plus-position instance.
     *
     * <p>The fallback is used only until a semantic command is available for this instance. It is
     * not a model object, packet, or controller reference.</p>
     */
    public BlendBlockEntityRendererBuilder<T> syncedSkinnedAnimation(BlendAnimationKey fallbackAnimation) {
        if (snapshotFactory != null) {
            throw new IllegalStateException("Choose either syncedSkinnedAnimation, staticRestPose, or a custom snapshotFactory");
        }
        snapshotFactory = new SyncedSkinnedBlockEntitySnapshotFactory<>(
                modelKey, Objects.requireNonNull(fallbackAnimation, "fallbackAnimation"));
        return this;
    }

    /** Builds the adapter after an extraction-only snapshot construction path was selected. */
    public BlendBlockEntityRenderer<T> build() {
        if (snapshotFactory == null) {
            throw new IllegalStateException("A BlendBlockEntityRenderer requires an extraction-only snapshotFactory");
        }
        return new BlendBlockEntityRenderer<>(context, modelKey, renderer, snapshotFactory);
    }
}
