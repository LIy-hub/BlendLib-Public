package com.liy.blendlib.examples.runnable;

/** Server-safe, smooth requested trajectory. Animation uses measured displacement, never this target. */
public final class ExampleBlendSpaceMotion {
    public static final String TAG = "blendlib_blendspace";
    private ExampleBlendSpaceMotion() { }

    /** Sixteen seconds: rest, cosine acceleration, cruise, cosine deceleration, then reverse. */
    public static double requestedHorizontalVelocity(int tick) {
        int phase = Math.floorMod(tick, 320);
        int segment = phase % 160;
        double speed;
        if (segment < 20 || segment >= 140) speed = 0;
        else if (segment < 60) speed = .14 * (1 - Math.cos((segment - 20) * Math.PI / 40)) / 2;
        else if (segment < 100) speed = .14;
        else speed = .14 * (1 + Math.cos((segment - 100) * Math.PI / 40)) / 2;
        return phase < 160 ? speed : -speed;
    }
}
