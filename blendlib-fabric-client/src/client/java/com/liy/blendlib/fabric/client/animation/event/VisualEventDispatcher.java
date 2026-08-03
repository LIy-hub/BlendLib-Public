package com.liy.blendlib.fabric.client.animation.event;

import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.core.animation.runtime.AnimationAdvance;
import com.liy.blendlib.core.animation.runtime.AnimationVisualEvent;

import java.util.Objects;

/**
 * Stateless extraction-side fan-out for the immutable visual events from one controller advance.
 *
 * <p>This class preserves the controller-produced order and only forwards an instance key plus an
 * immutable event. It owns no controller, model, or mutable animation state.</p>
 */
public final class VisualEventDispatcher {
    /**
     * Delivers the presentation events from one advance to the supplied listener.
     *
     * <p>A missing listener is a valid no-op so callers can keep extraction independent from
     * optional presentation integrations.</p>
     *
     * @return the number of delivered events
     */
    public int dispatch(BlendInstanceKey instanceKey, AnimationAdvance advance, VisualEventListener listener) {
        Objects.requireNonNull(instanceKey, "instanceKey");
        Objects.requireNonNull(advance, "advance");
        if (listener == null) {
            return 0;
        }

        int dispatched = 0;
        for (AnimationVisualEvent visualEvent : advance.visualEvents()) {
            listener.onVisualEvent(instanceKey, visualEvent);
            dispatched++;
        }
        return dispatched;
    }
}
