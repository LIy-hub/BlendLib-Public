package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.core.model.Bounds;
import com.liy.blendlib.core.model.Transform;
import java.util.List;

/**
 * Immutable, generation-scoped render data consumed by {@link ModelRenderSnapshot}.
 *
 * <p>Handle creation belongs to a reload/apply phase. Implementations must not do resource I/O,
 * JSON parsing, GLB parsing, or animation sampling when queried by a backend.</p>
 */
public interface ModelRenderHandle {
    BlendModelKey modelKey();

    long generation();

    Bounds bounds();

    /** Uniform conversion from descriptor model units into Minecraft block units. */
    float unitsToBlocksScale();

    /**
     * Static or rigid primitives prepared for the P4 submit path.
     *
     * <p>A skinned handle deliberately returns an empty list here. Its prepared source geometry
     * is exposed through {@link #skinnedPrimitives()} and can only become renderable after
     * extraction has captured CPU-skinned output into a {@link SkinnedRenderSnapshot}.</p>
     */
    List<PreparedRenderPrimitive> primitives();

    /**
     * Prepared skinned source primitives for the P5 CPU-skinning extraction path.
     *
     * <p>Static, rigid, and missing-model handles have no such primitives.</p>
     */
    default List<PreparedSkinnedRenderPrimitive> skinnedPrimitives() {
        return List.of();
    }

    /** Whether this handle requires a captured {@link SkinnedRenderSnapshot} before submit. */
    default boolean skinned() {
        return false;
    }

    Transform nodeTransform(int nodeIndex);

    boolean missingModel();
}
