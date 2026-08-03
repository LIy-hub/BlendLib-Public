package com.liy.blendlib.fabric.client.api;

import com.liy.blendlib.fabric.client.render.ModelRenderBackend;
import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import com.liy.blendlib.fabric.client.render.RenderSubmissionContext;
import com.liy.blendlib.fabric.client.render.RenderVisibility;
import com.liy.blendlib.fabric.client.perf.ClientRenderMeasurementCollector;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Objects;
import net.minecraft.client.renderer.SubmitNodeCollector;

/**
 * Low-level 26.1.2 renderer facade for consumers that already prepared a model snapshot.
 *
 * <p>This type deliberately accepts a {@link ModelRenderSnapshot}, rather than an entity, a
 * block entity, a model key, or a resource manager. It therefore cannot trigger a registry
 * lookup, resource I/O, JSON parsing, or GLB parsing on the submit path.</p>
 */
public final class BlendRenderer {
    private final ModelRenderBackend backend;

    public BlendRenderer(ModelRenderBackend backend) {
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    /**
     * Submits an already prepared snapshot through the current adapter backend.
     *
     * <p>A snapshot whose prepared visibility is {@code CULLED} is not passed to the backend.
     * This method performs no model lookup or extraction work.</p>
     */
    public void submit(ModelRenderSnapshot snapshot, PoseStack poseStack, SubmitNodeCollector collector) {
        submit(snapshot, new RenderSubmissionContext(poseStack, collector));
    }

    /** Submits an already prepared snapshot with an already validated public render context. */
    public void submit(ModelRenderSnapshot snapshot, RenderSubmissionContext context) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(context, "context");
        if (snapshot.visibility() == RenderVisibility.CULLED) {
            return;
        }
        long submitStartedNanos = ClientRenderMeasurementCollector.startSubmit();
        try {
            backend.submit(snapshot, context);
        } finally {
            ClientRenderMeasurementCollector.finishSubmit(submitStartedNanos, snapshot.handle().modelKey());
        }
    }
}
