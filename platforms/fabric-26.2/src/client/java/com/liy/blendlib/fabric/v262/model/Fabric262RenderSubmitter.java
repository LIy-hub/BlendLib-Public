package com.liy.blendlib.fabric.v262.model;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Objects;
import net.minecraft.client.renderer.SubmitNodeCollector;

/**
 * Standard public-API submit facade for prepared Minecraft 26.2 Fabric snapshots.
 *
 * <p><strong>Platform target:</strong> Fabric on Minecraft 26.2. It deliberately has no renderer
 * registration responsibility and no resource/JSON/GLB access. The adapter-owned
 * {@code Fabric262HostRenderDispatcher} supplies an already acquired snapshot from each real
 * entity, block-entity, or item callback at the final client render seam.</p>
 */
public final class Fabric262RenderSubmitter {
    /**
     * Submits one open immutable snapshot through public Blaze3D collector APIs.
     *
     * @param snapshot open generation-pinned prepared model snapshot
     * @param poseStack caller-owned pose stack
     * @param collector public Minecraft submit collector
     */
    public void submit(
            Fabric262RenderSnapshot snapshot,
            PoseStack poseStack,
            SubmitNodeCollector collector) {
        Fabric262RenderSnapshot checkedSnapshot = Objects.requireNonNull(snapshot, "snapshot");
        submit(checkedSnapshot, poseStack, collector, checkedSnapshot.frame());
    }

    /**
     * Submits an open generation-pinned snapshot with a frame captured by the native host seam.
     *
     * <p>Item renderers receive packed light and overlay values only at the public special-renderer
     * submit callback. This overload keeps their snapshot pin immutable while still using those
     * callback-owned values; it performs no lookup, I/O, parsing, or provider discovery.</p>
     *
     * @param snapshot open generation-pinned prepared model snapshot
     * @param poseStack caller-owned pose stack
     * @param collector public Minecraft submit collector
     * @param frame immutable callback-owned render frame
     */
    public void submit(
            Fabric262RenderSnapshot snapshot,
            PoseStack poseStack,
            SubmitNodeCollector collector,
            Fabric262FrameState frame) {
        Fabric262RenderSnapshot checkedSnapshot = Objects.requireNonNull(snapshot, "snapshot");
        submit(checkedSnapshot, poseStack, collector, frame, checkedSnapshot.preparedHandle().restPose());
    }

    /**
     * Submits an open generation-pinned snapshot with the exact extraction-frozen rigid palette.
     *
     * <p>This is the only animation-to-render handoff. The supplied pose has already sampled its
     * source and advanced its per-host controller; this submit method does neither.</p>
     *
     * @param snapshot open generation-pinned prepared model snapshot
     * @param poseStack caller-owned pose stack
     * @param collector public Minecraft submit collector
     * @param frame immutable callback-owned render frame
     * @param pose immutable generation-bound palette frozen during extraction
     */
    public void submit(
            Fabric262RenderSnapshot snapshot,
            PoseStack poseStack,
            SubmitNodeCollector collector,
            Fabric262FrameState frame,
            Fabric262PoseSnapshot pose) {
        Fabric262RenderSnapshot checkedSnapshot = Objects.requireNonNull(snapshot, "snapshot");
        checkedSnapshot.preparedHandle().submit(
                Objects.requireNonNull(poseStack, "poseStack"),
                Objects.requireNonNull(collector, "collector"),
                Objects.requireNonNull(frame, "frame"),
                Objects.requireNonNull(pose, "pose"));
    }
}
