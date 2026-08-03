package com.liy.blendlib.fabric.client.render;

/** Prepared visibility decision. A culled snapshot is never submitted to the backend. */
public enum RenderVisibility {
    VISIBLE,
    CULLED
}
