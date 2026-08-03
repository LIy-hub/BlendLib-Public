package com.liy.blendlib.fabric.client.animation.event;

import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.core.animation.runtime.AnimationVisualEvent;

/**
 * Receives one presentation-only animation event for a concrete client instance.
 *
 * <p>The callback deliberately has no return value and receives no controller or mutable
 * instance state. It is therefore limited to observing the extracted visual event.</p>
 */
@FunctionalInterface
public interface VisualEventListener {
    void onVisualEvent(BlendInstanceKey instanceKey, AnimationVisualEvent visualEvent);
}
