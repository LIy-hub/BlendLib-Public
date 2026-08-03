package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.core.animation.runtime.PreparedSkinnedGeometry;
import java.util.Objects;

/**
 * Immutable reload-time binding for one skinned primitive.
 *
 * <p>The source geometry is retained only for extraction-side CPU skinning. Render submit never
 * reads it: it consumes the matching immutable output captured in a
 * {@link SkinnedRenderSnapshot}.</p>
 */
public record PreparedSkinnedRenderPrimitive(
        int nodeIndex, int skinIndex, PreparedSkinnedGeometry geometry, RenderMaterial material) {
    public PreparedSkinnedRenderPrimitive {
        if (nodeIndex < 0) {
            throw new IllegalArgumentException("nodeIndex must be non-negative");
        }
        if (skinIndex < 0) {
            throw new IllegalArgumentException("skinIndex must be non-negative");
        }
        geometry = Objects.requireNonNull(geometry, "geometry");
        material = Objects.requireNonNull(material, "material");
    }
}
