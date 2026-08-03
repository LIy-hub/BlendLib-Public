package com.liy.blendlib.fabric.client.animation;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.core.animation.AnimationChannel;
import com.liy.blendlib.core.animation.AnimationClip;
import com.liy.blendlib.core.animation.AnimationPath;
import com.liy.blendlib.core.animation.Interpolation;
import com.liy.blendlib.core.animation.runtime.AnimationControllerDefinition;
import com.liy.blendlib.core.animation.runtime.AnimationState;
import com.liy.blendlib.core.animation.runtime.LocalPose;
import com.liy.blendlib.core.animation.runtime.PoseSampler;
import com.liy.blendlib.core.model.ModelNode;
import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;

import java.util.List;
import java.util.Map;

final class ClientAnimationTestFixtures {
    static final BlendAnimationKey IDLE = BlendAnimationKey.parse("blendlib:test_idle");
    static final BlendAnimationKey WALK = BlendAnimationKey.parse("blendlib:test_walk");
    static final BlendModelKey MODEL = BlendModelKey.parse("blendlib:test_model");
    static final BlendModelKey ALTERNATE_MODEL = BlendModelKey.parse("blendlib:test_alternate_model");

    private ClientAnimationTestFixtures() {
    }

    static AnimationControllerDefinition definition() {
        AnimationClip clip = new AnimationClip(
                "test_idle",
                List.of(new AnimationChannel(
                        0,
                        AnimationPath.TRANSLATION,
                        Interpolation.LINEAR,
                        new float[]{0.0F, 1.0F},
                        new float[]{0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F}
                ))
        );
        AnimationState state = new AnimationState(IDLE, clip, true, 1.0D, 0.0D, null, List.of());
        return new AnimationControllerDefinition(IDLE, Map.of(IDLE, state));
    }

    static AnimationControllerDefinition twoStateDefinition() {
        AnimationClip clip = new AnimationClip(
                "test_two_state",
                List.of(new AnimationChannel(
                        0,
                        AnimationPath.TRANSLATION,
                        Interpolation.LINEAR,
                        new float[]{0.0F, 1.0F},
                        new float[]{0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F}
                ))
        );
        AnimationState idle = new AnimationState(IDLE, clip, true, 1.0D, 0.0D, null, List.of());
        AnimationState walk = new AnimationState(WALK, clip, true, 1.0D, 0.0D, null, List.of());
        return new AnimationControllerDefinition(IDLE, Map.of(IDLE, idle, WALK, walk));
    }

    static LocalPose pose(float translationX) {
        return new LocalPose(Map.of(0, new Transform(
                new Vec3(translationX, 0.0F, 0.0F),
                Quaternion.IDENTITY,
                Vec3.ONE
        )));
    }

    static PoseSampler sampler() {
        return new PoseSampler(List.of(
                new ModelNode(0, "Root", Transform.IDENTITY, List.of(), -1, -1, false)));
    }

    static PoseCacheKey poseKey(BlendInstanceKey instanceKey, long generation, long sampleRevision) {
        return poseKey(instanceKey, MODEL, generation, sampleRevision);
    }

    static PoseCacheKey poseKey(
            BlendInstanceKey instanceKey,
            BlendModelKey modelKey,
            long generation,
            long sampleRevision
    ) {
        return new PoseCacheKey(instanceKey, modelKey, generation, IDLE, sampleRevision);
    }
}
