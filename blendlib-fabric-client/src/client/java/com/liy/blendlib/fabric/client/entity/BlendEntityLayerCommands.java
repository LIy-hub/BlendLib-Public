package com.liy.blendlib.fabric.client.entity;

import com.liy.blendlib.core.animation.v2.AnimationV2Command;
import java.util.List;
import net.minecraft.world.entity.Entity;

/** Extraction-only command source. Empty means continue; repeated sequence values are deduplicated. */
@FunctionalInterface
public interface BlendEntityLayerCommands<E extends Entity> {
    List<AnimationV2Command> commands(E entity, BlendEntitySnapshotRequest request);

    /**
     * Adapts tick-based cues without consumer caches or lifecycle event registrations.
     * Keep the returned source for the renderer lifetime; do not create one per extraction.
     * Repeated sequences keep their first captured command; stale sequences are ignored.
     */
    static <E extends Entity> BlendEntityLayerCommands<E> fromCues(BlendEntityLayerCues<? super E> cues) {
        java.util.Objects.requireNonNull(cues, "cues");
        Object source = new Object();
        return (entity, request) -> {
            var runtime = com.liy.blendlib.fabric.client.api.BlendLibClientServices.skinnedAnimationRuntime();
            var model = com.liy.blendlib.fabric.client.api.BlendLibClientServices.models().resolve(request.modelKey());
            if (model.missing()) return List.of();
            return runtime.captureEntityLayerCues(source, entity, entity.getId(), request.modelKey(),
                    model.generationId(), request.clientGameTick() + (double) request.partialTick(),
                    cues.cues(entity, request));
        };
    }
}
