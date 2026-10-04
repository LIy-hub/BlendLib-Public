package com.liy.blendlib.examples.runnable;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.core.animation.rules.LocomotionInputs;
import com.liy.blendlib.core.animation.v2.ModelAnimationLayers;
import java.util.List;
import java.util.Map;

/** Immutable consumer configuration; only extraction captures server-measured movement inputs. */
public final class ExampleLocomotionScene {
    public static final String PROPERTY = "blendlib.examples.locomotionRules";
    public static final BlendModelKey MODEL = BlendModelKey.parse(ExampleContent.MOD_ID + ":locomotion_actor");
    public static final BlendAnimationKey RUN = BlendAnimationKey.parse(ExampleContent.MOD_ID + ":run");

    private ExampleLocomotionScene() { }

    /** Read once when the renderer is registered, never from extraction or submit. */
    public static boolean enabled() { return Boolean.getBoolean(PROPERTY); }

    public static List<ModelAnimationLayers.Layer> layers() {
        var original = ExampleAnimationScene.layers();
        var base = original.getFirst();
        return List.of(new ModelAnimationLayers.Layer(base.id(), base.priority(), base.mode(),
                base.weight(), base.bones(), BlendAnimationKey.parse(ExampleContent.MOD_ID + ":idle")), original.get(1));
    }

    /** Units are horizontal blocks per server tick, measured after collision resolution. */
    public static LocomotionInputs inputs(boolean grounded, double horizontalSpeed) {
        return new LocomotionInputs(Map.of("grounded", grounded), Map.of("speed", horizontalSpeed));
    }
}
