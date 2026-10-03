package com.liy.blendlib.fabric.client.item;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.core.animation.runtime.AnimationVisualEvent;
import com.liy.blendlib.core.limits.BlendAssetLimits;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Item-local crossings only: descriptor next, loop and speed are deliberately irrelevant. */
final class ItemVisualEventCursor {
    static final double MAX_CATCH_UP_SECONDS = 1.0;
    private BlendModelKey model;
    private long generation;
    private long revision;
    private boolean active;
    private boolean rebaseAfterConsume;

    void invalidate() { active = false; }
    boolean rebaseAfterConsume() { return rebaseAfterConsume; }
    private List<AnimationVisualEvent> drop() { rebaseAfterConsume = true; return List.of(); }

    boolean requiresBaseline(BlendModelKey model, long generation, long revision) {
        return !active || !model.equals(this.model) || generation != this.generation || revision != this.revision;
    }

    /** Mutates the baseline before returning the immutable, bounded batch to its owner. */
    List<AnimationVisualEvent> consume(BlendModelKey model, long generation,
            ItemAnimationPlayback.EventInterval interval, double duration, List<AnimationVisualEvent> markers) {
        boolean baseline = requiresBaseline(model, generation, interval.revision());
        rebaseAfterConsume = baseline;
        this.model = model;
        this.generation = generation;
        revision = interval.revision();
        active = true;
        if (baseline || duration <= 0 || markers.isEmpty() || interval.end() <= interval.start()) return List.of();
        // Clamp non-looping time before trimming catch-up: the endpoint is still crossed when
        // ONCE returns the pose to zero, and time after a clip ends is not marker history.
        double end = interval.mode() == ItemAnimationPlayback.Mode.LOOP
                ? interval.end() : Math.min(duration, interval.end());
        double start = Math.max(interval.start(), end - MAX_CATCH_UP_SECONDS);
        // A huge finite time can round end - 1 back onto end. Consume and rebase that
        // numerically unusable interval so lowering speed can recover on the next sample.
        if (end <= start) return drop();
        if (interval.mode() != ItemAnimationPlayback.Mode.LOOP) {
            return markers.stream().filter(event -> event.timeSeconds() > start && event.timeSeconds() <= end).toList();
        }
        double firstCycle = Math.floor(start / duration);
        double lastCycle = Math.floor(end / duration);
        // Avoid both unbounded work and ambiguous floating-point loop indexes.
        if (!Double.isFinite(lastCycle) || lastCycle >= 0x1.0p52) return drop();
        if (lastCycle - firstCycle > BlendAssetLimits.MAX_LOOP_CYCLES_PER_ADVANCE) return List.of();
        var scheduled = new ArrayList<Occurrence>();
        for (var event : markers) {
            if (event.timeSeconds() > duration) continue;
            // Division can round just below an exact integer at a marker endpoint. Inspect
            // one neighboring cycle on each side and compare the reconstructed occurrence,
            // rather than treating a rounded quotient as an exact count. At most four rejected
            // candidates per marker keep this bounded by the event budget plus marker count.
            long first = Math.max(0L, (long) Math.floor((start - event.timeSeconds()) / duration) - 1L);
            long last = (long) Math.floor((end - event.timeSeconds()) / duration) + 1L;
            for (long cycle = first; cycle <= last; cycle++) {
                double time = cycle * duration + event.timeSeconds();
                if (time > start && time <= end) {
                    if (scheduled.size() == BlendAssetLimits.MAX_VISUAL_EVENTS_PER_ADVANCE) return List.of();
                    scheduled.add(new Occurrence(time, cycle, event));
                }
            }
        }
        scheduled.sort(Comparator.comparingDouble(Occurrence::seconds).thenComparingDouble(Occurrence::cycle));
        return scheduled.stream().map(Occurrence::event).toList();
    }

    private record Occurrence(double seconds, double cycle, AnimationVisualEvent event) { }
}
