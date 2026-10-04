package com.liy.blendlib.core.animation;

import java.util.Objects;

/** Immutable dense LINEAR/STEP weight keys, independent of transform paths. */
public final class MorphWeightChannel {
    private final int targetNode, targetCount;
    private final Interpolation interpolation;
    private final float[] times, values;
    public MorphWeightChannel(int targetNode, int targetCount, Interpolation interpolation, float[] times, float[] values) {
        this.interpolation = Objects.requireNonNull(interpolation, "interpolation");
        Objects.requireNonNull(times, "times"); Objects.requireNonNull(values, "values");
        if (targetNode < 0 || targetCount < 1 || targetCount > 8 || times.length == 0
                || (interpolation != Interpolation.LINEAR && interpolation != Interpolation.STEP)
                || values.length != (long) times.length * targetCount) throw new IllegalArgumentException("Invalid morph weight channel shape");
        for (int i = 0; i < times.length; i++) {
            if (!Float.isFinite(times[i]) || times[i] < 0 || (i > 0 && !(times[i] > times[i - 1]))) throw new IllegalArgumentException("Invalid morph key times");
        }
        for (float value : values) if (!Float.isFinite(value) || value < -2 || value > 2) throw new IllegalArgumentException("Morph key outside [-2,2]");
        this.targetNode = targetNode; this.targetCount = targetCount;
        this.times = times.clone(); this.values = values.clone();
    }
    public int targetNode() { return targetNode; }
    public int targetCount() { return targetCount; }
    public Interpolation interpolation() { return interpolation; }
    public int keyCount() { return times.length; }
    public float keyTime(int key) { return times[key]; }
    public float keyValue(int key, int target) {
        if (key < 0 || key >= keyCount() || target < 0 || target >= targetCount) throw new IndexOutOfBoundsException();
        return values[key * targetCount + target];
    }
    public float durationSeconds() { return times[times.length - 1]; }
    public float[] times() { return times.clone(); }
    public float[] values() { return values.clone(); }
}
