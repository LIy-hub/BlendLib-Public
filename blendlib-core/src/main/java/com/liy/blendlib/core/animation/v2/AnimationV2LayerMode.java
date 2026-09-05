package com.liy.blendlib.core.animation.v2;

/** How one frozen layer contributes to a composed v2 pose. */
public enum AnimationV2LayerMode {
    /** Replaces the rest pose through normalized weighted interpolation. */
    OVERRIDE,
    /** Applies a rest-relative delta after override composition. */
    ADDITIVE
}
