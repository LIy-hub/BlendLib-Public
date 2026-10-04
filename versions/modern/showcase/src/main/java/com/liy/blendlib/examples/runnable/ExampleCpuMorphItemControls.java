package com.liy.blendlib.examples.runnable;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.runtime.MorphFrameOverrides;
import java.util.Map;

/** Stateless, cosmetic squeeze derived from ordinary stack count; shared with packaged verification. */
public final class ExampleCpuMorphItemControls {
    public static final BlendModelKey MODEL = BlendModelKey.parse("cpu_morph:static_face_actor");
    public static final int MAX_STACK_SIZE = 64;
    private static final BlendResourceId BLINK = BlendResourceId.parse("cpu_morph:blink");
    private static final BlendResourceId SMILE = BlendResourceId.parse("cpu_morph:smile");

    private ExampleCpuMorphItemControls() { }

    /** One item is relaxed; a full stack closes the eyes and turns the smile into a squeeze. */
    public static MorphFrameOverrides capture(int stackCount) {
        // Saturate this example's input mapping, not the library's validated morph weights.
        int count = Math.max(1, Math.min(MAX_STACK_SIZE, stackCount));
        float squeeze = (count - 1F) / (MAX_STACK_SIZE - 1F);
        // Breath is deliberately omitted, preserving the authored nonzero default on every frame.
        return new MorphFrameOverrides(Map.of(BLINK, squeeze, SMILE, 1F - 2F * squeeze));
    }
}
