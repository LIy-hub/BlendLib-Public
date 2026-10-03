package com.liy.blendlib.fabric.client.animation.runtime.procedural;

import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.fabric.client.animation.runtime.ClientAnimationPoseModifier;

/** Rotation-only component. Call reset on removal, teleport, or an application-defined discontinuity. */
public interface ProceduralPoseComponent extends ClientAnimationPoseModifier {
    /** Clears all retained simulation state; stateless components do nothing. */
    default void reset() { }

    /** Clears simulation state for one instance; stateless components do nothing. */
    default void reset(BlendInstanceKey instanceKey) { }
}
