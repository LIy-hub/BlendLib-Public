package com.liy.blendlib.fabric.client.procedural;

/** Bounded result states for the experimental client-only two-bone solver. */
public enum ClientIkStatus {
    REACHED,
    CLAMPED_FAR,
    CLAMPED_NEAR,
    DEGENERATE,
    REJECTED
}
