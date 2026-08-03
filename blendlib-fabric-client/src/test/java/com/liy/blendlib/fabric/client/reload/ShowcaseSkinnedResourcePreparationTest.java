package com.liy.blendlib.fabric.client.reload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.AnimationClip;
import com.liy.blendlib.core.animation.runtime.AnimationControllerDefinition;
import com.liy.blendlib.core.animation.runtime.NodePalette;
import com.liy.blendlib.core.animation.runtime.PoseSampler;
import com.liy.blendlib.core.animation.runtime.SocketWorldTransform;
import com.liy.blendlib.core.asset.AssetBytes;
import com.liy.blendlib.core.descriptor.AnimationDefinition;
import com.liy.blendlib.core.descriptor.AnimationEventDefinition;
import com.liy.blendlib.core.descriptor.AnimationStateDefinition;
import com.liy.blendlib.core.descriptor.MaterialDefinition;
import com.liy.blendlib.core.loader.ModelAssetLoader;
import com.liy.blendlib.core.model.ModelAsset;
import com.liy.blendlib.core.model.ModelProfile;
import com.liy.blendlib.core.model.SocketTable;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.fabric.client.render.PreparedSkinnedRenderPrimitive;
import com.liy.blendlib.fabric.client.render.SkinnedRenderHandle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for the real Showcase skinned runtime resource through strict load and P5
 * handle preparation. The test reads the checked-in resource paths directly; it deliberately
 * keeps no copied descriptor, GLB, or PNG fixture.
 */
class ShowcaseSkinnedResourcePreparationTest {
    private static final long GENERATION = 73L;
    private static final BlendModelKey MODEL_KEY =
            BlendModelKey.parse("blendlib_showcase:showcase_animation/showcase_actor");
    private static final BlendResourceId MESH_ID =
            BlendResourceId.parse("blendlib_showcase:models3d/showcase_animation/showcase_actor.glb");
    private static final BlendResourceId TEXTURE_ID = BlendResourceId.parse(
            "blendlib_showcase:textures/blendlib/showcase_animation/showcase_actor__showcaseanimationsurface.png");
    private static final BlendResourceId IDLE_KEY = BlendResourceId.parse("blendlib_showcase:idle");
    private static final BlendResourceId WALK_KEY = BlendResourceId.parse("blendlib_showcase:walk");
    private static final BlendResourceId ATTACK_KEY = BlendResourceId.parse("blendlib_showcase:attack");
    private static final BlendResourceId ATTACK_WHOOSH = BlendResourceId.parse("blendlib_showcase:attack_whoosh");
    private static final BlendResourceId TIP_SOCKET_KEY = BlendResourceId.parse("blendlib_showcase:tip");
    private static final String TIP_SOCKET_PATH =
            "ShowcaseAnimationRoot/ShowcaseAnimationArmature/ShowcaseRootBone/ShowcaseTipBone";
    private static final int TIP_NODE_INDEX = 0;

    @Test
    void realShowcaseSkinnedResourceStrictlyLoadsIntoCanonicalP5Handle() throws IOException {
        ModelAsset asset = loadShowcaseAsset();

        assertEquals(MODEL_KEY.resourceId(), asset.modelKey());
        assertEquals(MODEL_KEY.descriptorResourceId(), asset.descriptorId());
        assertEquals(GENERATION, asset.generation());
        assertEquals(ModelProfile.SKINNED_V1, asset.profile());
        assertEquals(List.of(4), asset.defaultSceneRoots());
        assertEquals("ShowcaseAnimationRoot", asset.nodes().get(asset.defaultSceneRoots().getFirst()).name());
        assertNotNull(asset.skeleton());
        assertFalse(asset.skeleton().skins().isEmpty());
        assertFalse(asset.primitives().isEmpty());
        assertTrue(asset.primitives().stream().allMatch(primitive -> primitive.geometry().skinned()));

        assertEquals(List.of("attack", "idle", "walk"), asset.clips().stream().map(AnimationClip::name).toList());
        AnimationDefinition animation = asset.animationDefinition();
        assertNotNull(animation);
        assertEquals(IDLE_KEY, animation.initialState());
        assertEquals(Set.of(IDLE_KEY, WALK_KEY, ATTACK_KEY), animation.states().keySet());
        assertEquals("idle", state(animation, IDLE_KEY).clip());
        assertEquals("walk", state(animation, WALK_KEY).clip());
        AnimationStateDefinition attack = state(animation, ATTACK_KEY);
        assertEquals("attack", attack.clip());
        AnimationEventDefinition whoosh = attack.events().getFirst();
        assertEquals(1, attack.events().size());
        assertEquals(ATTACK_WHOOSH, whoosh.eventKey());
        assertEquals(0.25D, whoosh.timeSeconds());
        assertTrue(whoosh.timeSeconds() >= 0.0D);
        assertTrue(whoosh.timeSeconds() <= clip(asset, attack.clip()).durationSeconds());

        MaterialDefinition material = asset.materials().get("ShowcaseAnimationSurface");
        assertNotNull(material);
        assertEquals(TEXTURE_ID, material.baseColor());

        SkinnedRenderHandle handle = SkinnedRenderHandle.prepare(MODEL_KEY, asset);
        assertEquals(MODEL_KEY, handle.modelKey());
        assertEquals(GENERATION, handle.generation());
        assertTrue(handle.skinned());
        assertFalse(handle.missingModel());
        assertTrue(handle.primitives().isEmpty());
        assertEquals(asset.primitives().size(), handle.skinnedPrimitives().size());
        assertTrue(handle.skinnedPrimitives().stream().allMatch(this::isPreparedSkinnedPrimitive));
    }

    @Test
    void realShowcaseTipSocketFollowsWalkAtTwoCanonicalTimes() throws IOException {
        ModelAsset asset = loadShowcaseAsset();
        SocketTable.Socket tipSocket = asset.sockets().get(TIP_SOCKET_KEY);
        assertNotNull(tipSocket);
        assertEquals(TIP_SOCKET_PATH, tipSocket.nodePath());
        assertEquals(TIP_NODE_INDEX, tipSocket.nodeIndex());
        assertEquals("ShowcaseTipBone", asset.nodes().get(tipSocket.nodeIndex()).name());

        AnimationControllerDefinition controller = AnimationControllerDefinition.fromModelAsset(asset);
        PoseSampler sampler = PoseSampler.fromModelAsset(asset);
        Transform positivePhase = queryWalkTip(asset, controller, sampler, 7.0D / 24.0D);
        Transform negativePhase = queryWalkTip(asset, controller, sampler, 19.0D / 24.0D);

        assertEquals(0.07F, positivePhase.translation().x(), 1.0e-4F);
        assertEquals(-0.07F, negativePhase.translation().x(), 1.0e-4F);
        assertEquals(0.60F, positivePhase.translation().y(), 1.0e-4F);
        assertEquals(0.60F, negativePhase.translation().y(), 1.0e-4F);
        assertEquals(0.0F, positivePhase.translation().z(), 1.0e-4F);
        assertEquals(0.0F, negativePhase.translation().z(), 1.0e-4F);
        assertTrue(positivePhase.translation().x() > 0.0F);
        assertTrue(negativePhase.translation().x() < 0.0F);
        assertNotEquals(positivePhase.translation(), negativePhase.translation());
    }

    private static Transform queryWalkTip(
            ModelAsset asset, AnimationControllerDefinition controller, PoseSampler sampler, double seconds
    ) {
        NodePalette palette = NodePalette.fromCanonicalScene(
                sampler.sample(controller.state(BlendAnimationKey.fromResourceId(WALK_KEY)), seconds),
                asset.nodes(),
                asset.defaultSceneRoots());
        return SocketWorldTransform.query(asset, palette, TIP_SOCKET_KEY).orElseThrow();
    }

    private static ModelAsset loadShowcaseAsset() throws IOException {
        Path assetRoot = showcaseAssetRoot();
        Path descriptorPath = assetRoot.resolve("blend_models/showcase_animation/showcase_actor.json");
        Path glbPath = assetRoot.resolve("models3d/showcase_animation/showcase_actor.glb");
        Path texturePath = assetRoot.resolve("textures/blendlib/showcase_animation/showcase_actor__showcaseanimationsurface.png");
        assertTrue(Files.isRegularFile(descriptorPath), () -> "Missing Showcase descriptor: " + descriptorPath);
        assertTrue(Files.isRegularFile(glbPath), () -> "Missing Showcase GLB: " + glbPath);
        assertTrue(Files.isRegularFile(texturePath), () -> "Missing external Showcase PNG: " + texturePath);
        assertTrue(Files.size(texturePath) > 0L, () -> "Showcase PNG must not be empty: " + texturePath);

        AssetBytes descriptor = new AssetBytes(MODEL_KEY.descriptorResourceId(), Files.readAllBytes(descriptorPath));
        AssetBytes glb = new AssetBytes(MESH_ID, Files.readAllBytes(glbPath));
        return new ModelAssetLoader().load(MODEL_KEY.resourceId(), GENERATION, descriptor, requested -> {
            assertEquals(MESH_ID, requested);
            return glb;
        });
    }

    private static AnimationStateDefinition state(AnimationDefinition animation, BlendResourceId key) {
        AnimationStateDefinition state = animation.states().get(key);
        assertNotNull(state, () -> "Missing descriptor animation state: " + key);
        return state;
    }

    private static AnimationClip clip(ModelAsset asset, String name) {
        return asset.clips().stream()
                .filter(clip -> clip.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing decoded GLB clip: " + name));
    }

    private boolean isPreparedSkinnedPrimitive(PreparedSkinnedRenderPrimitive primitive) {
        return primitive.nodeIndex() >= 0
                && primitive.skinIndex() >= 0
                && primitive.geometry().vertexCount() > 0
                && primitive.geometry().joints().length == primitive.geometry().vertexCount() * 4
                && primitive.geometry().weights().length == primitive.geometry().vertexCount() * 4
                && primitive.material() != null;
    }

    private static Path showcaseAssetRoot() {
        return Path.of(System.getProperty("blendlib.projectDir"))
                .getParent()
                .resolve("blendlib-showcase")
                .resolve("src")
                .resolve("main")
                .resolve("resources")
                .resolve("assets")
                .resolve("blendlib_showcase");
    }
}
