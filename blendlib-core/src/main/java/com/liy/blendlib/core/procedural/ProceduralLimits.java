package com.liy.blendlib.core.procedural;

/** Fixed, deliberately small budgets for the experimental X3 procedural boundary. */
public final class ProceduralLimits {
    public static final int MAX_HIERARCHY_DEPTH = 256;
    public static final int MAX_HOOKS = 32;
    public static final int MAX_COMMANDS_PER_FRAME = 256;
    public static final int MAX_DIAGNOSTICS_PER_FRAME = 64;
    /** One slot is reserved only after an overflow, so the marker can never displace a retained ERROR. */
    public static final int MAX_DIAGNOSTIC_DETAILS = MAX_DIAGNOSTICS_PER_FRAME - 1;
    public static final int MAX_VISUAL_EVENTS_PER_FRAME = 128;
    public static final int MAX_VISUAL_EVENT_REPLAY_FENCE = 4_096;
    public static final int MAX_ATTACHMENTS = 64;
    public static final int MAX_ATTACHMENT_DEPTH = 8;
    public static final int MAX_ATTACHMENT_GRAPH_MODELS = 4_096;
    /** Raw descriptors are bounded before duplicate child edges are collapsed. */
    public static final int MAX_ATTACHMENT_GRAPH_RAW_DESCRIPTOR_VISITS = 4_096;
    public static final int MAX_ATTACHMENT_GRAPH_EDGES = 4_096;
    public static final int MAX_ACTIVE_ATTACHMENT_GRAPH_CLAIMS = 256;
    public static final int MAX_IDENTIFIER_UTF16_CODE_UNITS = 256;
    public static final int MAX_MESSAGE_UTF16_CODE_UNITS = 256;
    public static final int MAX_CUSTOM_EVENT_FIELDS = 16;
    public static final int MAX_CUSTOM_EVENT_FIELD_UTF16_CODE_UNITS = 64;
    public static final float MIN_SCALE_MULTIPLIER = 1.0F / 64.0F;
    public static final float MAX_SCALE_MULTIPLIER = 64.0F;
    public static final float MAX_TRANSLATION_MAGNITUDE = 16_384.0F;
    public static final float DIRECTION_EPSILON = 1.0E-6F;

    private ProceduralLimits() {
    }
}
