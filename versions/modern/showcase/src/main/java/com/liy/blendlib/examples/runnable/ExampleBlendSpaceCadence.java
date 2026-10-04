package com.liy.blendlib.examples.runnable;

/** Explicit consumer policy shared by 1D/2D examples; no world mutation or inferred stride length. */
public final class ExampleBlendSpaceCadence {
    public static final String PROPERTY = "blendlib.examples.blendspaceCadence";
    /** The fixture's authored reference horizontal speed, in collision-resolved blocks per server tick. */
    public static final double REFERENCE_SPEED = .06;
    public static final double MIN_MULTIPLIER = .5;
    public static final double MAX_MULTIPLIER = 2;

    private ExampleBlendSpaceCadence() { }

    /** Read once during client registration; leaving it off preserves both original fixed-cycle modes. */
    public static boolean enabled() { return Boolean.getBoolean(PROPERTY); }

    /** Idle intentionally keeps a positive clock. This must receive measured speed, never requested velocity. */
    public static double multiplier(double measuredHorizontalSpeed) {
        if (!Double.isFinite(measuredHorizontalSpeed) || measuredHorizontalSpeed < 0)
            throw new IllegalArgumentException("measured horizontal speed must be finite and nonnegative");
        return Math.max(MIN_MULTIPLIER, Math.min(MAX_MULTIPLIER, measuredHorizontalSpeed / REFERENCE_SPEED));
    }
}
