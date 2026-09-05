package com.liy.blendlib.core.animation.v2;

/** Hard, input-independent limits for the internal experimental v2 animation runtime. */
public final class AnimationV2Limits {
    public static final int MAX_CONTROLLERS_PER_INSTANCE = 16;
    public static final int MAX_LAYERS_PER_CONTROLLER = 16;
    public static final int MAX_STATES_PER_CONTROLLER = 128;
    public static final int MAX_BONES_PER_SCHEMA = 4_096;
    public static final int MAX_KEYFRAMES_PER_CLIP = 4_096;
    public static final int MAX_DIAGNOSTICS_PER_EVALUATION = 64;
    public static final int MAX_NEXT_TRANSITIONS_PER_ADVANCE = 64;
    /** Maximum exact automatic timeline segments retained per controller in one observer snapshot. */
    public static final int MAX_OBSERVER_TRAVERSAL_SEGMENTS_PER_ADVANCE = 128;
    /** Bounded callback-to-owner command queue capacity per instance. */
    public static final int MAX_INGRESS_QUEUE_PER_INSTANCE = 256;
    /** Maximum complete controller/sequence groups the owner applies or reconciles in a single frame. */
    public static final int MAX_INGRESS_DRAIN_PER_ADVANCE = 128;
    /** Maximum synchronous frame commands accepted before explicit owner-side backpressure. */
    public static final int MAX_FRAME_COMMANDS_PER_ADVANCE = MAX_INGRESS_QUEUE_PER_INSTANCE;
    /**
     * Maximum owner-held raw commands: one complete callback queue plus one complete synchronous frame batch.
     * Additional callback work remains in its bounded ingress queue until this snapshot has been fully scheduled.
     */
    public static final int MAX_OWNER_COMMAND_BACKLOG_PER_INSTANCE =
            MAX_INGRESS_QUEUE_PER_INSTANCE + MAX_FRAME_COMMANDS_PER_ADVANCE;
    public static final int MAX_IDENTIFIER_UTF16_CODE_UNITS = 256;
    public static final int MIN_PRIORITY = -1_000;
    public static final int MAX_PRIORITY = 1_000;
    public static final double MIN_PLAYBACK_SPEED = 1.0D / 64.0D;
    public static final double MAX_PLAYBACK_SPEED = 64.0D;
    public static final double MAX_TRANSITION_SECONDS = 60.0D;
    public static final double MAX_CLIP_DURATION_SECONDS = 600.0D;
    public static final double MAX_ADVANCE_SECONDS = 600.0D;
    /**
     * Tolerance for non-timeline numeric domains such as clip compatibility and transition/interpolation cleanup.
     * Playhead advancement and observer traversal boundaries intentionally use exact representable-double ordering.
     */
    public static final double EPSILON = 1.0E-8D;

    private AnimationV2Limits() {
    }

    static void requirePriority(int value, String name) {
        if (value < MIN_PRIORITY || value > MAX_PRIORITY) {
            throw new IllegalArgumentException(name + " must be in [" + MIN_PRIORITY + ", " + MAX_PRIORITY + "]");
        }
    }

    static void requireCanonicalIdLength(String value, String name) {
        if (value.length() > MAX_IDENTIFIER_UTF16_CODE_UNITS) {
            throw new IllegalArgumentException(name + " exceeds " + MAX_IDENTIFIER_UTF16_CODE_UNITS + " UTF-16 code units");
        }
    }

    static void requireSpeed(double value, String name) {
        if (!isValidPlaybackSpeed(value)) {
            throw new IllegalArgumentException(name + " must be finite and in ["
                    + MIN_PLAYBACK_SPEED + ", " + MAX_PLAYBACK_SPEED + "]");
        }
    }

    /** Returns whether one externally supplied playback rate is inside the fixed v2 safety interval. */
    public static boolean isValidPlaybackSpeed(double value) {
        return Double.isFinite(value) && value >= MIN_PLAYBACK_SPEED && value <= MAX_PLAYBACK_SPEED;
    }

    /** Returns whether a state rate multiplied by a semantic intent rate remains consumable. */
    public static boolean isValidEffectivePlaybackSpeed(double stateSpeed, double intentSpeed) {
        double effective = stateSpeed * intentSpeed;
        return isValidPlaybackSpeed(effective);
    }
}
