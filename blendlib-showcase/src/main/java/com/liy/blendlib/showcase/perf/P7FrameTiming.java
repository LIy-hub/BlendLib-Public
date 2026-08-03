package com.liy.blendlib.showcase.perf;

/**
 * One measured real-client frame, expressed as monotonic durations in nanoseconds.
 *
 * <p>Animation preparation and submit CPU are deliberately separate so a renderer cannot hide a
 * hot extraction cost inside a nominal frame-time result.</p>
 */
public record P7FrameTiming(long frameNanos, long animationPreparationNanos, long submitCpuNanos) {
    public P7FrameTiming {
        if (frameNanos < 0L || animationPreparationNanos < 0L || submitCpuNanos < 0L
                || animationPreparationNanos > frameNanos || submitCpuNanos > frameNanos) {
            throw new IllegalArgumentException("P7 frame timing is invalid");
        }
    }

    /** Creates a timing from instrumentation stamps supplied in strict chronological order. */
    public static P7FrameTiming fromStamps(
            long frameStartNanos,
            long animationPreparationStartNanos,
            long animationPreparationEndNanos,
            long submitStartNanos,
            long submitEndNanos,
            long frameEndNanos) {
        if (frameStartNanos > animationPreparationStartNanos
                || animationPreparationStartNanos > animationPreparationEndNanos
                || animationPreparationEndNanos > submitStartNanos
                || submitStartNanos > submitEndNanos
                || submitEndNanos > frameEndNanos) {
            throw new IllegalArgumentException("P7 timing stamps must be chronological");
        }
        return new P7FrameTiming(
                Math.subtractExact(frameEndNanos, frameStartNanos),
                Math.subtractExact(animationPreparationEndNanos, animationPreparationStartNanos),
                Math.subtractExact(submitEndNanos, submitStartNanos));
    }
}
