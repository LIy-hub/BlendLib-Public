package com.liy.blendlib.core.procedural;

import com.liy.blendlib.api.ModelInstance;
import com.liy.blendlib.core.animation.v2.AnimationV2EvaluationSnapshot;
import java.util.Objects;

/** Read-only input to a procedural hook; it deliberately exposes no world, renderer, resource manager, or runtime. */
public record ProceduralHookContext(ModelInstance modelInstance, AnimationV2EvaluationSnapshot evaluation) {
    public ProceduralHookContext {
        modelInstance = Objects.requireNonNull(modelInstance, "modelInstance");
        evaluation = Objects.requireNonNull(evaluation, "evaluation");
    }
}
