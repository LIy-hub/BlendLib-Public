package com.liy.blendlib.core.animation.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.descriptor.AnimationDefinition;
import com.liy.blendlib.core.descriptor.AnimationStateDefinition;
import com.liy.blendlib.core.model.Bounds;
import com.liy.blendlib.core.model.ModelAsset;
import com.liy.blendlib.core.model.MeshPrimitive;
import com.liy.blendlib.core.model.ModelPrimitive;
import com.liy.blendlib.core.model.ModelProfile;
import com.liy.blendlib.core.model.SocketTable;
import com.liy.blendlib.core.model.Vec3;
import java.util.Map;
import java.util.Set;
import com.liy.blendlib.core.animation.AnimationChannel;
import com.liy.blendlib.core.animation.AnimationClip;
import com.liy.blendlib.core.animation.AnimationPath;
import com.liy.blendlib.core.animation.Interpolation;
import com.liy.blendlib.core.animation.runtime.AnimationState;
import com.liy.blendlib.core.animation.runtime.PoseSampler;
import com.liy.blendlib.core.model.ModelNode;
import com.liy.blendlib.core.model.Transform;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnimationV2NativeClipTest {
    @Test
    void preservesStepDiscontinuityAndSparseNodeOrderRatherThanBakingEndpoints() {
        AnimationState state = state(Interpolation.STEP,
                new float[] {0, 0, 0, 3, 0, 0, 1, 0, 0}, new float[] {0, 0.5F, 1});
        PoseSampler sampler = sampler();
        AnimationV2Clip clip = AnimationV2Clip.fromState(sampler, state, List.of(8, 2));
        assertEquals(2, clip.boneCount());
        assertEquals(0, clip.sample(0.49D).transform(0).translation().x());
        assertEquals(3, clip.sample(0.5D).transform(0).translation().x());
        assertEquals(3, clip.sample(0.9D).transform(0).translation().x());
        assertEquals(1, clip.sample(1).transform(0).translation().x());
        assertEquals(Transform.IDENTITY, clip.sample(0.5D).transform(1));
    }

    @Test
    void preservesIntermediateKeysForPublicAndAllocationFreeSampling() {
        AnimationState state = state(Interpolation.LINEAR, new float[] {
                0, 0, 0, 3, 0, 0, 1, 0, 0}, new float[] {0, 0.5F, 1});
        PoseSampler sampler = sampler();
        AnimationV2Clip clip = AnimationV2Clip.fromState(sampler, state, List.of(8, 2));
        // A baked linear endpoint interpolation would incorrectly return 0.5.
        assertEquals(3, clip.sample(0.5D).transform(0).translation().x(), 1.0e-6F);
        AnimationV2TransformScratch scratch = new AnimationV2TransformScratch();
        for (double time : new double[] {0, 0.1D, 0.5D, 0.9D, 1}) {
            clip.sampleInto(time, 0, scratch);
            assertEquals(sampler.sampleNode(state, time, 8), scratch.toTransform());
            assertEquals(scratch.toTransform(), clip.sample(time).transform(0));
        }
    }

    @Test
    void nativeClipSamplingClampsRawEndpointDespiteLoopAndDescriptorSpeed() {
        AnimationState state = state(Interpolation.LINEAR,
                new float[] {0, 0, 0, 4, 0, 0}, new float[] {0, 1});
        AnimationV2Clip clip = AnimationV2Clip.fromState(sampler(), state, List.of(8, 2));
        assertEquals(1.0D, clip.durationSeconds());
        assertEquals(2, clip.sample(0.5D).transform(0).translation().x());
        assertEquals(4, clip.sample(1).transform(0).translation().x());
        assertEquals(4, clip.sample(100).transform(0).translation().x());
    }

    @Test
    void modelLayerAdapterMapsDensePoseSlotsBackToSparseModelIndices() {
        ModelAnimationLayers layers = new ModelAnimationLayers(asset(sampler().nodes()), List.of(layer("Bone")));
        var runtime = new AnimationV2InstanceRuntime(layers.plan());
        var local = layers.localPose(runtime.advance(0.5D).pose());
        assertEquals(Set.of(2, 8), local.transforms().keySet());
        assertEquals(Transform.IDENTITY, local.transform(2));
        assertEquals(2, local.transform(8).translation().x(), 1.0e-6F);
        assertThrows(IllegalArgumentException.class,
                () -> layers.localPose(new AnimationV2Pose(List.of(Transform.IDENTITY))));
    }

    @Test
    void modelLayerAdapterRejectsUnknownMasksAndAmbiguousNodeNames() {
        ModelAsset valid = asset(sampler().nodes());
        assertThrows(IllegalArgumentException.class,
                () -> new ModelAnimationLayers(valid, List.of(layer("Missing"))));
        var duplicateNames = List.of(
                new ModelNode(2, "Bone", Transform.IDENTITY, List.of(8), -1, -1, false),
                new ModelNode(8, "Bone", Transform.IDENTITY, List.of(), -1, -1, false));
        ModelAsset ambiguous = asset(duplicateNames);
        assertThrows(IllegalArgumentException.class,
                () -> new ModelAnimationLayers(ambiguous, List.of(layer("Bone"))));
    }

    private static ModelAnimationLayers.Layer layer(String name) {
        return new ModelAnimationLayers.Layer(BlendResourceId.parse("native_clip:base"), 0,
                AnimationV2LayerMode.OVERRIDE, 1, List.of(new BoneMask.NamedWeight(name, 1)),
                BlendAnimationKey.parse("native_clip:test"));
    }

    private static ModelAsset asset(List<ModelNode> nodes) {
        BlendResourceId key = BlendResourceId.parse("native_clip:test");
        AnimationClip clip = state(Interpolation.LINEAR,
                new float[] {0, 0, 0, 4, 0, 0}, new float[] {0, 1}).clip();
        MeshPrimitive geometry = new MeshPrimitive("surface",
                new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0},
                new float[] {0, 0, 1, 0, 0, 1, 0, 0, 1},
                new float[] {0, 0, 1, 0, 0, 1}, new int[] {0, 1, 2}, null, null);
        return new ModelAsset(key, BlendResourceId.parse("native_clip:model.json"), 1,
                ModelProfile.RIGID_V1, 1, Map.of(),
                new AnimationDefinition(key, Map.of(key,
                        new AnimationStateDefinition("test", true, 1, 0, null, List.of()))),
                nodes, List.of(2), List.of(new ModelPrimitive(8, 0, 0, geometry)), null, List.of(clip), new SocketTable(Map.of()),
                new Bounds(Vec3.ZERO, Vec3.ZERO), List.of());
    }

    private static PoseSampler sampler() {
        return new PoseSampler(List.of(
                new ModelNode(2, "Root", Transform.IDENTITY, List.of(8), -1, -1, false),
                new ModelNode(8, "Bone", Transform.IDENTITY, List.of(), -1, -1, false)));
    }

    private static AnimationState state(Interpolation interpolation, float[] values, float[] times) {
        return new AnimationState(BlendAnimationKey.parse("native_clip:test"),
                new AnimationClip("test", List.of(new AnimationChannel(
                        8, AnimationPath.TRANSLATION, interpolation, times, values))),
                true, 2.0D, 0, null, List.of());
    }
}
