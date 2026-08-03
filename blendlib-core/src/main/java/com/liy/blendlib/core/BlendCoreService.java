package com.liy.blendlib.core;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Objects;

/**
 * Minimal P1 pure-Java core service marker.
 *
 * <p>Future GLB, animation, and validation services belong behind this module boundary.
 * This marker deliberately contains no platform integration.</p>
 */
public final class BlendCoreService {
    private static final String MARKER = "blendlib-core-p1";

    private BlendCoreService() {
    }

    public static String marker() {
        return MARKER;
    }

    public static BlendResourceId retain(BlendResourceId resourceId) {
        return Objects.requireNonNull(resourceId, "resourceId");
    }
}
