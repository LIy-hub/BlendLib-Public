package com.liy.blendlib.fabric.client.item;

import com.liy.blendlib.api.BlendAnimationKey;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Client-only playback for one retained stack instance. Call on the client/extraction thread. */
public final class ItemAnimationPlayback {
    /** LOOP wraps, ONCE returns to the first pose, HOLD retains the final pose. */
    public enum Mode { LOOP, ONCE, HOLD }

    private final LongSupplier clock;
    private BlendAnimationKey animation;
    private Mode mode = Mode.LOOP;
    private double seconds;
    private double speed = 1.0;
    private long lastNanos;
    private boolean playing = true;
    private ItemAnimationObservation.Sample lastSample;

    ItemAnimationObservation.Sample lastSample() { return lastSample; }

    void sampled(com.liy.blendlib.api.BlendModelKey model, long generation, double seconds, double duration) {
        lastSample = new ItemAnimationObservation.Sample(model, animation, generation, seconds, duration);
    }

    ItemAnimationObservation observe(long currentGeneration) {
        return new ItemAnimationObservation(animation, mode, speed, playing, seconds,
                java.util.Optional.ofNullable(lastSample),
                lastSample != null && lastSample.generation() == currentGeneration);
    }

    ItemAnimationPlayback(BlendAnimationKey animation, LongSupplier clock) {
        this.animation = Objects.requireNonNull(animation, "animation");
        this.clock = Objects.requireNonNull(clock, "clock");
        lastNanos = clock.getAsLong();
    }

    /** Restarts a named descriptor state at its first pose using an explicit end behavior. */
    public ItemAnimationPlayback play(BlendAnimationKey animation, Mode mode) {
        Objects.requireNonNull(animation, "animation");
        Objects.requireNonNull(mode, "mode");
        this.animation = animation;
        this.mode = mode;
        seconds = 0;
        playing = true;
        lastNanos = clock.getAsLong();
        return this;
    }

    /** Freezes the current time; repeated pauses are harmless. */
    public ItemAnimationPlayback pause() { update(); playing = false; return this; }
    /** Continues from the current time without including time spent paused. */
    public ItemAnimationPlayback resume() { update(); playing = true; return this; }
    /** Stops at the first pose, retaining the selected animation and speed. */
    public ItemAnimationPlayback stop() { update(); seconds = 0; playing = false; return this; }
    /** Sets a finite non-negative multiplier. Zero freezes time without changing playing state. */
    public ItemAnimationPlayback speed(double speed) {
        requireTime(speed, "speed");
        update();
        this.speed = speed;
        return this;
    }
    /** Seeks in raw clip seconds; next extraction clamps or wraps to the loaded clip duration. */
    public ItemAnimationPlayback seek(double seconds) {
        requireTime(seconds, "seconds");
        update();
        this.seconds = seconds;
        return this;
    }
    public BlendAnimationKey animation() { return animation; }
    public Mode mode() { return mode; }
    public double speed() { return speed; }
    public boolean playing() { return playing; }

    double sample(double durationSeconds) {
        requireTime(durationSeconds, "durationSeconds");
        update();
        if (durationSeconds == 0) {
            seconds = 0;
            if (mode != Mode.LOOP) playing = false;
        } else if (seconds >= durationSeconds) {
            switch (mode) {
                case LOOP -> seconds %= durationSeconds;
                case ONCE -> { seconds = 0; playing = false; }
                case HOLD -> { seconds = durationSeconds; playing = false; }
            }
        }
        return seconds;
    }

    private void update() {
        long now = clock.getAsLong();
        double elapsed = Math.max(0, now - lastNanos) / 1_000_000_000.0;
        lastNanos = now;
        if (playing) {
            double next = seconds + elapsed * speed;
            seconds = Double.isFinite(next) ? next : Double.MAX_VALUE;
        }
    }

    private static void requireTime(double value, String name) {
        if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException(name + " must be finite and non-negative");
    }
}
