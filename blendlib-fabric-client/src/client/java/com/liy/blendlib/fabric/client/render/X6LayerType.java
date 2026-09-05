package com.liy.blendlib.fabric.client.render;

/** The eight bounded X6 adapter-side layer intents; invalid material/target combinations fail closed during preparation. */
public enum X6LayerType {
    GLOW,
    OVERLAY,
    DAMAGE_FLASH,
    ATTACHMENT,
    OUTLINE,
    SHADOW,
    SECONDARY_TEXTURE,
    PER_BONE_PART_TEXTURE
}
