package com.liy.blendlib.fabric.client.blockentity;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.core.animation.runtime.MorphFrameOverrides;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.fabric.client.api.BlendLibClientServices;
import com.liy.blendlib.fabric.client.animation.extract.SkinnedExtractionRequest;
import com.liy.blendlib.fabric.client.render.*;
import java.util.Objects;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Stateless block-local CPU deformation. World access and controls are extraction-only. */
final class StaticMorphBlockEntitySnapshotFactory<T extends BlockEntity> implements BlendBlockEntitySnapshotFactory<T> {
    private final BlendModelKey modelKey;
    private final BlendBlockEntityMorphControls<? super T> controls;

    StaticMorphBlockEntitySnapshotFactory(BlendModelKey modelKey, BlendBlockEntityMorphControls<? super T> controls) {
        this.modelKey = Objects.requireNonNull(modelKey, "modelKey");
        this.controls = controls;
    }

    @Override public ModelRenderSnapshot create(T blockEntity, BlendBlockEntitySnapshotRequest request) {
        Objects.requireNonNull(blockEntity, "blockEntity");
        Objects.requireNonNull(request, "request");
        if (!modelKey.equals(request.modelKey())) throw new IllegalArgumentException("Model key mismatch");
        var runtime = BlendLibClientServices.skinnedAnimationRuntime();
        long revision = runtime.captureExtractionLifecycleRevision();
        var model = BlendLibClientServices.models().resolve(modelKey);
        Level level = blockEntity.getLevel();
        var visibility = request.animationVisible() ? RenderVisibility.VISIBLE : RenderVisibility.CULLED;
        if (!model.missing() && runtime.hasActivePlayConnection() && isCurrent(blockEntity, level, request)) {
            var weights = captureControls(blockEntity, request, controls,
                    () -> isCurrent(blockEntity, level, request));
            if (weights.isPresent()) {
                var frame = runtime.extractStaticMorph(modelKey, model.generationId(), request.instanceKey(), revision,
                        weights.orElseThrow(), new SkinnedExtractionRequest(Transform.IDENTITY, request.packedLight(),
                                OverlayTexture.NO_OVERLAY, 0xFFFFFFFF, visibility,
                                new CullingMetadata(model.renderHandle().bounds(), true)));
                if (frame.isPresent()) return frame.orElseThrow().renderSnapshot();
            }
        }
        ModelRenderHandle fallback = model.missing() ? model.renderHandle()
                : new MissingModelRenderHandle(modelKey, model.generationId());
        return new ModelRenderSnapshot(fallback, Transform.IDENTITY, request.packedLight(), OverlayTexture.NO_OVERLAY,
                0xFFFFFFFF, visibility, new CullingMetadata(fallback.bounds(), true));
    }

    /** Capture once and reject callbacks that replace/remove their block or leave its world. */
    static <T extends BlockEntity> java.util.Optional<MorphFrameOverrides> captureControls(T blockEntity,
            BlendBlockEntitySnapshotRequest request, BlendBlockEntityMorphControls<? super T> controls,
            java.util.function.BooleanSupplier current) {
        if (!current.getAsBoolean()) return java.util.Optional.empty();
        var weights = controls == null ? MorphFrameOverrides.empty()
                : Objects.requireNonNull(controls.weights(blockEntity, request), "captured morph controls");
        return current.getAsBoolean() ? java.util.Optional.of(weights) : java.util.Optional.empty();
    }

    /** A removed/replaced object cannot borrow the identity of a replacement at the same position. */
    private static boolean isCurrent(BlockEntity blockEntity, Level level, BlendBlockEntitySnapshotRequest request) {
        return level != null && net.minecraft.client.Minecraft.getInstance().level == level
                && blockEntity.getLevel() == level && !blockEntity.isRemoved()
                && blockEntity.getBlockPos().asLong() == request.instanceKey().packedBlockPos()
                && level.dimension().identifier().toString().equals(request.instanceKey().dimension().value())
                && level.hasChunkAt(blockEntity.getBlockPos())
                && level.getBlockEntity(blockEntity.getBlockPos()) == blockEntity;
    }
}
