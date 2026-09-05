package com.liy.blendlib.fabric.client.host;

/**
 * Supported held-item contexts. This enum intentionally excludes the ordinary marker-item path,
 * which remains owned by {@code BlendLibItemModelBindings} and is not an X4 held-item adapter.
 */
public enum X4HeldItemContext {
    THIRD_PERSON,
    GROUND,
    FIXED,
    HEAD
}
