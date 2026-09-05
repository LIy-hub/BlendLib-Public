package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Objects;

/** Immutable, actual standard-backend draw instruction retaining a catalog-owned geometry binding. */
public record X6DrawPrimitive(
        BlendResourceId partId, X6GeometryBinding binding, RenderMaterial material, int argbTint) {
    public X6DrawPrimitive {
        partId = X6Ids.requireId(partId, "partId");
        binding = Objects.requireNonNull(binding, "binding");
        material = Objects.requireNonNull(material, "material");
    }

    /** Compatibility convenience for static/rigid callers. It fails closed for a skinned draw. */
    public X6DrawPrimitive(
            BlendResourceId partId, PreparedRenderPrimitive primitive, RenderMaterial material, int argbTint) {
        this(partId, new X6GeometryBinding.StaticBinding(primitive), material, argbTint);
    }

    /** Returns the retained static primitive only when this draw is static/rigid. */
    public PreparedRenderPrimitive primitive() {
        if (binding instanceof X6GeometryBinding.StaticBinding staticBinding) {
            return staticBinding.primitive();
        }
        throw new IllegalStateException("This X6 draw retains a skinned binding, not a static primitive");
    }
}
