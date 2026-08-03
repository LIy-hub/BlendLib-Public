package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.core.model.Bounds;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import java.util.List;
import java.util.Objects;

/**
 * Fixed, finite magenta/black fallback that remains renderable when a requested asset fails.
 *
 * <p>Its geometry and materials are independent of the failed descriptor, GLB, and texture.
 * The requested key is retained only for diagnostics and generation-scoped deduplication.</p>
 */
public final class MissingModelRenderHandle implements ModelRenderHandle {
    public static final int MAGENTA_ARGB = 0xFFFF00FF;
    public static final int BLACK_ARGB = 0xFF000000;

    private static final Bounds BOUNDS = new Bounds(new Vec3(-0.25f, -0.25f, 0.0f), new Vec3(0.25f, 0.25f, 0.0f));
    private static final StaticGeometry MAGENTA_TRIANGLE = StaticGeometry.of(
            new float[] {-0.25f, -0.25f, 0.0f, 0.25f, -0.25f, 0.0f, 0.25f, 0.25f, 0.0f},
            new float[] {0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f},
            new float[] {0.0f, 0.0f, 1.0f, 0.0f, 1.0f, 1.0f},
            new int[] {0, 1, 2});
    private static final StaticGeometry BLACK_TRIANGLE = StaticGeometry.of(
            new float[] {-0.25f, -0.25f, 0.0f, 0.25f, 0.25f, 0.0f, -0.25f, 0.25f, 0.0f},
            new float[] {0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f},
            new float[] {0.0f, 0.0f, 1.0f, 1.0f, 0.0f, 1.0f},
            new int[] {0, 1, 2});
    private static final List<PreparedRenderPrimitive> PRIMITIVES = List.of(
            new PreparedRenderPrimitive(0, MAGENTA_TRIANGLE, RenderMaterial.missing(MAGENTA_ARGB)),
            new PreparedRenderPrimitive(0, BLACK_TRIANGLE, RenderMaterial.missing(BLACK_ARGB)));

    private final BlendModelKey modelKey;
    private final long generation;

    public MissingModelRenderHandle(BlendModelKey modelKey, long generation) {
        this.modelKey = Objects.requireNonNull(modelKey, "modelKey");
        if (generation < 0) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
        this.generation = generation;
    }

    @Override
    public BlendModelKey modelKey() {
        return modelKey;
    }

    @Override
    public long generation() {
        return generation;
    }

    @Override
    public Bounds bounds() {
        return BOUNDS;
    }

    @Override
    public float unitsToBlocksScale() {
        return 1.0f;
    }

    @Override
    public List<PreparedRenderPrimitive> primitives() {
        return PRIMITIVES;
    }

    @Override
    public Transform nodeTransform(int nodeIndex) {
        if (nodeIndex != 0) {
            throw new IndexOutOfBoundsException("Missing model has one root node");
        }
        return Transform.IDENTITY;
    }

    @Override
    public boolean missingModel() {
        return true;
    }
}
