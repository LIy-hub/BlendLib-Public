package com.liy.blendlib.core.animation.v2;

/** Observable result of callback-thread command ingress into the bounded core queue. */
public enum AnimationV2IngressOutcome {
    QUEUED,
    QUEUE_OVERFLOW
}
