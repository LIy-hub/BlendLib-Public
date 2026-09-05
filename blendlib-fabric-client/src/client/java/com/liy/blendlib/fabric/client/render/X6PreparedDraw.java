package com.liy.blendlib.fabric.client.render;

import java.util.Objects;
import net.minecraft.client.renderer.rendertype.RenderType;

/** Plan-time material route binding; submit consumes it without rebuilding a RenderType lookup. */
record X6PreparedDraw(X6DrawPrimitive draw, RenderType renderType) {
    X6PreparedDraw {
        draw = Objects.requireNonNull(draw, "draw");
        renderType = Objects.requireNonNull(renderType, "renderType");
    }
}
