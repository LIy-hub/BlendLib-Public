package com.liy.blendlib.fabric.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.asset.AssetBytes;
import com.liy.blendlib.core.loader.ModelAssetLoader;
import com.liy.blendlib.core.model.ModelAsset;
import com.liy.blendlib.core.model.ModelNode;
import com.liy.blendlib.core.model.ModelProfile;
import com.liy.blendlib.fabric.client.animation.AnimationUpdateBucket;
import com.liy.blendlib.fabric.client.animation.ClientAnimationLifecycleBridge;
import com.liy.blendlib.fabric.client.animation.extract.SkinnedExtractionRequest;
import com.liy.blendlib.fabric.client.animation.runtime.SkinnedAnimationRuntime;
import com.liy.blendlib.fabric.client.animation.runtime.SkinnedAnimationRuntimeInput;
import com.liy.blendlib.fabric.client.reload.ClientModelRegistry;
import com.liy.blendlib.fabric.client.reload.LoadedModelHandle;
import com.liy.blendlib.fabric.client.reload.ModelRegistryGeneration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Real-resource P5 regression for the exported rigid fixture's complete extraction-to-submit
 * handoff. The test deliberately reads the committed P2 descriptor and GLB instead of building a
 * substitute animation fixture.
 */
class ShowcaseRigidPulseRuntimeTest {
    private static final long GENERATION = 97L;
    private static final BlendModelKey MODEL_KEY =
            BlendModelKey.parse("blendlib_showcase:fixtures/rigid_model");
    private static final BlendAnimationKey RIGID_PULSE =
            BlendAnimationKey.parse("blendlib_showcase:rigid_pulse");
    private static final BlendResourceId MESH_ID =
            BlendResourceId.parse("blendlib_showcase:models3d/fixtures/rigid_model.glb");

    @Test
    void committedRigidPulseUsesTheSharedRegistryAndCapturesANonIdentityNodePalette() throws IOException {
        ModelAsset asset = loadRigidAsset();
        assertEquals(ModelProfile.RIGID_V1, asset.profile());
        assertNotNull(asset.animationDefinition());
        assertEquals(RIGID_PULSE.resourceId(), asset.animationDefinition().initialState());

        StaticRigidRenderHandle handle = StaticRigidRenderHandle.prepare(MODEL_KEY, asset);
        LoadedModelHandle loaded = new LoadedModelHandle(MODEL_KEY, asset, handle);
        ClientModelRegistry models = new ClientModelRegistry();
        ClientAnimationLifecycleBridge lifecycle = new ClientAnimationLifecycleBridge(8);
        SkinnedAnimationRuntime runtime = new SkinnedAnimationRuntime(models, lifecycle);
        runtime.onPlayInit();
        models.publish(new ModelRegistryGeneration(GENERATION, Map.of(MODEL_KEY, loaded), Map.of(), List.of()));

        BlendInstanceKey.Entity instanceKey = lifecycle.entityKey(41);
        runtime.extract(new SkinnedAnimationRuntimeInput(
                MODEL_KEY,
                instanceKey,
                0L,
                0.0F,
                RIGID_PULSE,
                Optional.empty(),
                AnimationUpdateBucket.VISIBLE_NEAR,
                new SkinnedExtractionRequest(
                        com.liy.blendlib.core.model.Transform.IDENTITY,
                        0x00F000F0,
                        0,
                        0xFFFFFFFF,
                        RenderVisibility.VISIBLE,
                        new CullingMetadata(handle.bounds(), true))))
                .orElseThrow();
        var result = runtime.extract(new SkinnedAnimationRuntimeInput(
                MODEL_KEY,
                instanceKey,
                10L,
                0.0F,
                RIGID_PULSE,
                Optional.empty(),
                AnimationUpdateBucket.VISIBLE_NEAR,
                new SkinnedExtractionRequest(
                        com.liy.blendlib.core.model.Transform.IDENTITY,
                        0x00F000F0,
                        0,
                        0xFFFFFFFF,
                        RenderVisibility.VISIBLE,
                        new CullingMetadata(handle.bounds(), true))))
                .orElseThrow();

        ModelRenderSnapshot snapshot = result.frame().renderSnapshot();
        assertSame(handle, snapshot.handle());
        assertFalse(snapshot.handle().skinned());
        assertEquals(GENERATION, snapshot.generation());
        assertTrue(lifecycle.registry().find(instanceKey).isPresent());
        assertEquals(1, lifecycle.registry().size());

        RigidNodePaletteSnapshot palette = snapshot.rigidNodePalette();
        assertNotNull(palette, "rigid extraction must attach its captured palette before submit");
        int armNode = asset.nodes().stream()
                .filter(node -> "RigidArm".equals(node.name()))
                .mapToInt(ModelNode::index)
                .findFirst()
                .orElseThrow();
        float armX = palette.nodeTransform(armNode).translation().x();
        assertEquals(0.1499440F, armX, 1.0e-5F);
        assertTrue(Math.abs(armX) > 1.0e-4F, "rigid_pulse must not collapse to the rest-pose identity palette");
        assertEquals(
                palette.nodeTransform(armNode),
                Minecraft2612StaticRigidRenderBackend.nodeTransformFor(snapshot, armNode));
    }

    private static ModelAsset loadRigidAsset() throws IOException {
        Path assetRoot = Path.of(System.getProperty("blendlib.projectDir"))
                .getParent()
                .resolve("blendlib-showcase")
                .resolve("src")
                .resolve("main")
                .resolve("resources")
                .resolve("assets")
                .resolve("blendlib_showcase");
        Path descriptorPath = assetRoot.resolve("blend_models/fixtures/rigid_model.json");
        Path glbPath = assetRoot.resolve("models3d/fixtures/rigid_model.glb");
        assertTrue(Files.isRegularFile(descriptorPath), () -> "Missing rigid descriptor: " + descriptorPath);
        assertTrue(Files.isRegularFile(glbPath), () -> "Missing rigid GLB: " + glbPath);

        AssetBytes descriptor = new AssetBytes(MODEL_KEY.descriptorResourceId(), Files.readAllBytes(descriptorPath));
        AssetBytes glb = new AssetBytes(MESH_ID, Files.readAllBytes(glbPath));
        return new ModelAssetLoader().load(MODEL_KEY.resourceId(), GENERATION, descriptor, requested -> {
            assertEquals(MESH_ID, requested);
            return glb;
        });
    }
}
