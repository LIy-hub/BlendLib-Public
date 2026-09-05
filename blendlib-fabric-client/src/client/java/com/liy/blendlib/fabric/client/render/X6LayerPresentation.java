package com.liy.blendlib.fabric.client.render;

/** Frozen CPU/public-collector presentation operation for an already standard material route. */
public enum X6LayerPresentation {
    /** Normal material/geometry emission. */
    NONE,
    /** Offsets vertices along their prepared normals by a fixed bounded shell distance. */
    OUTLINE_SHELL,
    /** Flattens the existing mesh in model space under the normal public translucent route. */
    SHADOW_FLATTEN,
    /** Multiplies a frozen presentation tint distinct from the selected part's variant tint. */
    DAMAGE_FLASH
}
