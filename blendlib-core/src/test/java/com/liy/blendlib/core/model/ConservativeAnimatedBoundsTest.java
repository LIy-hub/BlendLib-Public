package com.liy.blendlib.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.AnimationChannel;
import com.liy.blendlib.core.animation.AnimationClip;
import com.liy.blendlib.core.animation.AnimationPath;
import com.liy.blendlib.core.animation.Interpolation;
import com.liy.blendlib.core.animation.runtime.AnimationState;
import com.liy.blendlib.core.animation.runtime.CpuSkinnedMesh;
import com.liy.blendlib.core.animation.runtime.CpuSkinner;
import com.liy.blendlib.core.animation.runtime.LocalPose;
import com.liy.blendlib.core.animation.runtime.NodePalette;
import com.liy.blendlib.core.animation.runtime.PoseSampler;
import com.liy.blendlib.core.animation.runtime.PreparedSkinnedGeometry;
import com.liy.blendlib.core.animation.runtime.SkinPalette;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConservativeAnimatedBoundsTest {
    private static final BlendResourceId MODEL_KEY = BlendResourceId.parse("bounds_test:model");
    private static final BlendResourceId DESCRIPTOR_ID = BlendResourceId.parse("bounds_test:blend_models/model.json");

    @Test
    void restOnlyAssetKeepsItsExactAxisAlignedBounds() {
        Bounds rest = new Bounds(new Vec3(-0.25f, 0.0f, -0.5f), new Vec3(1.0f, 2.0f, 0.5f));
        MeshPrimitive geometry = rigidGeometry();

        Bounds result = ConservativeAnimatedBounds.includeAnimations(
                rest, rigidNodes(), List.of(0),
                List.of(new ModelPrimitive(1, 0, 0, geometry)), null, List.of());

        assertSame(rest, result);
        assertEquals(rest, result);
    }

    @Test
    void rigidEnvelopeContainsEveryClipStateAndCrossFadeWithoutTemporalSampling() {
        AnimationClip moveX = translationClip("move-x", 1, 10.0f, 0.0f, 0.0f);
        AnimationClip moveY = translationClip("move-y", 0, 0.0f, 20.0f, 0.0f);
        ModelAsset asset = rigidAsset(1L, List.of(moveX, moveY));
        PoseSampler sampler = PoseSampler.fromModelAsset(asset);
        LocalPose xPose = sampler.sample(state("bounds_test:move_x", moveX), 1.0);
        LocalPose yPose = sampler.sample(state("bounds_test:move_y", moveY), 1.0);
        LocalPose blended = sampler.blend(xPose, yPose, 0.5);

        assertRigidPointInside(asset.bounds(), NodePalette.fromCanonicalScene(
                xPose, asset.nodes(), asset.defaultSceneRoots()), 1, new Vec3(1.0f, 0.0f, 0.0f));
        assertRigidPointInside(asset.bounds(), NodePalette.fromCanonicalScene(
                yPose, asset.nodes(), asset.defaultSceneRoots()), 1, new Vec3(1.0f, 0.0f, 0.0f));
        assertRigidPointInside(asset.bounds(), NodePalette.fromCanonicalScene(
                blended, asset.nodes(), asset.defaultSceneRoots()), 1, new Vec3(1.0f, 0.0f, 0.0f));
        assertTrue(asset.bounds().max().x() >= 11.0f);
        assertTrue(asset.bounds().max().y() >= 20.0f);
    }

    @Test
    void skinnedEnvelopeContainsInverseBindJointMotionBeyondTheRestBounds() {
        AnimationClip jointMove = translationClip("joint-move", 1, 12.0f, 0.0f, 0.0f);
        MeshPrimitive geometry = skinnedGeometry();
        Skin skin = new Skin("skin", 0, List.of(1), Matrix4.identity().copy());
        List<ModelNode> nodes = List.of(
                new ModelNode(0, "Root", Transform.IDENTITY, List.of(1, 2), -1, -1, false),
                new ModelNode(1, "Joint", Transform.IDENTITY, List.of(), -1, -1, false),
                new ModelNode(2, "Mesh", Transform.IDENTITY, List.of(), 0, 0, false));
        ModelAsset asset = new ModelAsset(
                MODEL_KEY, DESCRIPTOR_ID, 2L, ModelProfile.SKINNED_V1, 1.0, Map.of(), null,
                nodes, List.of(0), List.of(new ModelPrimitive(2, 0, 0, geometry)),
                new Skeleton(List.of(skin)), List.of(jointMove), new SocketTable(Map.of()),
                geometry.localBounds(), List.of());
        LocalPose pose = PoseSampler.fromModelAsset(asset).sample(state("bounds_test:joint_move", jointMove), 1.0);
        NodePalette nodePalette = NodePalette.fromCanonicalScene(pose, nodes, List.of(0));
        CpuSkinnedMesh skinned = CpuSkinner.skin(
                PreparedSkinnedGeometry.prepare(geometry), SkinPalette.from(skin, nodePalette));

        float[] positions = skinned.positions();
        for (int offset = 0; offset < positions.length; offset += 3) {
            assertInside(asset.bounds(), new Vec3(positions[offset], positions[offset + 1], positions[offset + 2]));
        }
        assertTrue(asset.bounds().max().x() >= 13.0f);
    }

    @Test
    void skinnedRestPaletteIsBoundedEvenWithoutAnimationClips() {
        MeshPrimitive geometry = skinnedGeometry();
        float[] translatedInverseBind = Matrix4.identity().copy();
        translatedInverseBind[12] = 7.0f;
        Skin skin = new Skin("offset-skin", 0, List.of(1), translatedInverseBind);
        List<ModelNode> nodes = List.of(
                new ModelNode(0, "Root", Transform.IDENTITY, List.of(1, 2), -1, -1, false),
                new ModelNode(1, "Joint", Transform.IDENTITY, List.of(), -1, -1, false),
                new ModelNode(2, "Mesh", Transform.IDENTITY, List.of(), 0, 0, false));

        ModelAsset asset = new ModelAsset(
                MODEL_KEY, DESCRIPTOR_ID, 4L, ModelProfile.SKINNED_V1, 1.0, Map.of(), null,
                nodes, List.of(0), List.of(new ModelPrimitive(2, 0, 0, geometry)),
                new Skeleton(List.of(skin)), List.of(), new SocketTable(Map.of()),
                geometry.localBounds(), List.of());
        LocalPose restPose = new LocalPose(Map.of(
                0, Transform.IDENTITY, 1, Transform.IDENTITY, 2, Transform.IDENTITY));
        CpuSkinnedMesh skinned = CpuSkinner.skin(
                PreparedSkinnedGeometry.prepare(geometry),
                SkinPalette.from(skin, NodePalette.fromCanonicalScene(restPose, nodes, List.of(0))));

        float[] positions = skinned.positions();
        for (int offset = 0; offset < positions.length; offset += 3) {
            assertInside(asset.bounds(), new Vec3(positions[offset], positions[offset + 1], positions[offset + 2]));
        }
        assertTrue(asset.bounds().max().x() >= 8.0f);
    }

    @Test
    void animatedHierarchyOverflowIsRejectedBeforeAHandleCanPublishNonFiniteBounds() {
        MeshPrimitive geometry = rigidGeometry();
        List<ModelNode> nodes = List.of(
                new ModelNode(0, "Root", Transform.IDENTITY, List.of(1), -1, -1, false),
                new ModelNode(1, "Mesh", new Transform(
                        Vec3.ZERO, Quaternion.IDENTITY, new Vec3(2.0f, 2.0f, 2.0f)), List.of(), 0, -1, false));
        AnimationClip overflow = new AnimationClip("overflow", List.of(new AnimationChannel(
                0, AnimationPath.SCALE, Interpolation.LINEAR, new float[] {0.0f, 1.0f},
                new float[] {1.0f, 1.0f, 1.0f, Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE})));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> new ModelAsset(
                MODEL_KEY, DESCRIPTOR_ID, 3L, ModelProfile.RIGID_V1, 1.0, Map.of(), null,
                nodes, List.of(0), List.of(new ModelPrimitive(1, 0, 0, geometry)), null,
                List.of(overflow), new SocketTable(Map.of()), geometry.localBounds(), List.of()));

        assertTrue(exception.getMessage().contains("finite float envelope"));
    }

    private static ModelAsset rigidAsset(long generation, List<AnimationClip> clips) {
        MeshPrimitive geometry = rigidGeometry();
        return new ModelAsset(
                MODEL_KEY, DESCRIPTOR_ID, generation, ModelProfile.RIGID_V1, 1.0, Map.of(), null,
                rigidNodes(), List.of(0), List.of(new ModelPrimitive(1, 0, 0, geometry)), null,
                clips, new SocketTable(Map.of()), geometry.localBounds(), List.of());
    }

    private static List<ModelNode> rigidNodes() {
        return List.of(
                new ModelNode(0, "Root", Transform.IDENTITY, List.of(1), -1, -1, false),
                new ModelNode(1, "Mesh", Transform.IDENTITY, List.of(), 0, -1, false));
    }

    private static AnimationClip translationClip(String name, int node, float x, float y, float z) {
        return new AnimationClip(name, List.of(new AnimationChannel(
                node, AnimationPath.TRANSLATION, Interpolation.LINEAR,
                new float[] {0.0f, 1.0f}, new float[] {0.0f, 0.0f, 0.0f, x, y, z})));
    }

    private static AnimationState state(String key, AnimationClip clip) {
        return new AnimationState(BlendAnimationKey.parse(key), clip, false, 1.0, 0.5, null, List.of());
    }

    private static MeshPrimitive rigidGeometry() {
        return new MeshPrimitive(
                "Base", new float[] {0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f},
                normals(), texCoords(), new int[] {0, 1, 2}, null, null);
    }

    private static MeshPrimitive skinnedGeometry() {
        return new MeshPrimitive(
                "Base", new float[] {1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f},
                normals(), texCoords(), new int[] {0, 1, 2},
                new int[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                new float[] {1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f});
    }

    private static float[] normals() {
        return new float[] {0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f};
    }

    private static float[] texCoords() {
        return new float[] {0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f};
    }

    private static void assertRigidPointInside(Bounds bounds, NodePalette palette, int nodeIndex, Vec3 localPoint) {
        assertInside(bounds, palette.worldTransform(nodeIndex).transformPoint(localPoint));
    }

    private static void assertInside(Bounds bounds, Vec3 point) {
        assertTrue(point.x() >= bounds.min().x() && point.x() <= bounds.max().x(), "x outside bounds");
        assertTrue(point.y() >= bounds.min().y() && point.y() <= bounds.max().y(), "y outside bounds");
        assertTrue(point.z() >= bounds.min().z() && point.z() <= bounds.max().z(), "z outside bounds");
    }
}
