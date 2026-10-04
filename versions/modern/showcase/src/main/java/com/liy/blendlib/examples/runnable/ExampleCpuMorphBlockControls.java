package com.liy.blendlib.examples.runnable;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.runtime.MorphFrameOverrides;
import java.util.Map;

/** Stateless presentation math shared by the live renderer and packaged verification. */
public final class ExampleCpuMorphBlockControls {
    public static final BlendModelKey MODEL = BlendModelKey.parse("cpu_morph:static_face_actor");
    private static final BlendResourceId BLINK = BlendResourceId.parse("cpu_morph:blink");
    private static final BlendResourceId SMILE = BlendResourceId.parse("cpu_morph:smile");

    private ExampleCpuMorphBlockControls() { }

    /** Positions vary phase only; neither the model root nor gameplay receives a world transform. */
    public static MorphFrameOverrides capture(int x, int y, int z, long clientGameTick, float partialTick) {
        if (clientGameTick < 0 || !Float.isFinite(partialTick))
            throw new IllegalArgumentException("Presentation time must be finite and non-negative");
        double phaseTicks = Math.floorMod(17L * x + 7L * y + 31L * z, 80L);
        // Bound the arithmetic before converting long world time to floating point.
        double seconds = (Math.floorMod(clientGameTick, 80L) + partialTick + phaseTicks) / 20.0;
        double cycle = seconds - Math.floor(seconds / 4.0) * 4.0;
        float blink = (float) Math.max(0, 1 - Math.abs(cycle - 2) * 8);
        float smile = (float) (.5 + .5 * Math.sin(seconds * Math.PI / 2));
        // Breath is deliberately omitted, preserving the authored nonzero default.
        return new MorphFrameOverrides(Map.of(BLINK, blink, SMILE, smile));
    }
}
