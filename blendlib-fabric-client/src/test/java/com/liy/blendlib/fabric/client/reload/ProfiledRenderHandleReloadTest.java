package com.liy.blendlib.fabric.client.reload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.AnimationChannel;
import com.liy.blendlib.core.animation.AnimationClip;
import com.liy.blendlib.core.animation.AnimationPath;
import com.liy.blendlib.core.animation.Interpolation;
import com.liy.blendlib.core.descriptor.MaterialDefinition;
import com.liy.blendlib.core.diagnostic.BlendDiagnostic;
import com.liy.blendlib.core.diagnostic.BlendDiagnosticCodes;
import com.liy.blendlib.core.model.Bounds;
import com.liy.blendlib.core.model.MeshPrimitive;
import com.liy.blendlib.core.model.ModelAsset;
import com.liy.blendlib.core.model.ModelNode;
import com.liy.blendlib.core.model.ModelPrimitive;
import com.liy.blendlib.core.model.ModelProfile;
import com.liy.blendlib.core.model.Skeleton;
import com.liy.blendlib.core.model.Skin;
import com.liy.blendlib.core.model.SocketTable;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.fabric.client.render.SkinnedRenderHandle;
import com.liy.blendlib.fabric.client.render.StaticRigidRenderHandle;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Reload-time profile dispatch regressions for the P5 CPU-skinning handle seam. */
class ProfiledRenderHandleReloadTest {
    private static final long GENERATION = 61L;
    private static final BlendModelKey RIGID_KEY = BlendModelKey.parse("reload_test:profiles/rigid");
    private static final BlendModelKey SKINNED_KEY = BlendModelKey.parse("reload_test:profiles/skinned");
    private static final BlendResourceId TEXTURE = BlendResourceId.parse("reload_test:textures/base.png");
    private static final String RIGID_SLOT = "RigidSurface";
    private static final String SKINNED_SLOT = "SkinSurface";

    @Test
    void publicationSelectsTheStrictProfileHandleAndPreservesExactModelGenerationIdentity() {
        ModelAsset rigid = rigidAsset(material(MaterialDefinition.Mode.OPAQUE));
        ModelAsset skinned = skinnedAsset(material(MaterialDefinition.Mode.CUTOUT));
        ModelRegistryGeneration generation = ClientModelReloadListener.createPublishedGeneration(new PreparedModelGeneration(
                GENERATION,
                Map.of(RIGID_KEY, rigid, SKINNED_KEY, skinned),
                Map.of(),
                List.of()));

        assertLoadedHandle(generation, RIGID_KEY, StaticRigidRenderHandle.class);
        assertLoadedHandle(generation, SKINNED_KEY, SkinnedRenderHandle.class);
        assertTrue(generation.primaryDiagnostic(RIGID_KEY).isEmpty());
        assertTrue(generation.primaryDiagnostic(SKINNED_KEY).isEmpty());
    }

    @Test
    void unsupportedSkinnedMaterialStillPublishesTheExistingMat004MissingHandle() {
        ModelRegistryGeneration generation = ClientModelReloadListener.createPublishedGeneration(new PreparedModelGeneration(
                GENERATION,
                Map.of(SKINNED_KEY, skinnedAsset(material(MaterialDefinition.Mode.ADDITIVE))),
                Map.of(),
                List.of()));

        ModelHandle handle = generation.find(SKINNED_KEY).orElseThrow();
        BlendDiagnostic diagnostic = generation.primaryDiagnostic(SKINNED_KEY).orElseThrow();
        assertTrue(handle instanceof MissingModelHandle);
        assertTrue(handle.missing());
        assertTrue(handle.renderHandle().missingModel());
        assertEquals(GENERATION, handle.generationId());
        assertEquals(SKINNED_KEY, handle.key());
        assertEquals(GENERATION, handle.renderHandle().generation());
        assertEquals(SKINNED_KEY, handle.renderHandle().modelKey());
        assertEquals(BlendDiagnosticCodes.MAT_004, diagnostic.code());
        assertEquals("/materials/SkinSurface/mode", diagnostic.location());
        assertTrue(diagnostic.message().contains("ADDITIVE_UNSUPPORTED_IN_P4"));
    }

    @Test
    void reloadPublicationUsesOnlyTheNewGenerationAnimatedEnvelopeAndRejectsAStaleReplacement() {
        ClientModelRegistry registry = new ClientModelRegistry();
        ModelAsset firstAsset = animatedRigidAsset(1L, 2.0F);
        ModelRegistryGeneration first = ClientModelReloadListener.createPublishedGeneration(new PreparedModelGeneration(
                1L, Map.of(RIGID_KEY, firstAsset), Map.of(), List.of()));
        registry.publish(first);
        Bounds firstBounds = registry.current().find(RIGID_KEY).orElseThrow().renderHandle().bounds();

        ModelAsset secondAsset = animatedRigidAsset(2L, 20.0F);
        ModelRegistryGeneration second = ClientModelReloadListener.createPublishedGeneration(new PreparedModelGeneration(
                2L, Map.of(RIGID_KEY, secondAsset), Map.of(), List.of()));
        registry.publish(second);
        Bounds secondBounds = registry.current().find(RIGID_KEY).orElseThrow().renderHandle().bounds();

        assertTrue(firstBounds.max().x() >= 3.0F);
        assertTrue(secondBounds.max().x() >= 21.0F);
        assertTrue(secondBounds.max().x() > firstBounds.max().x());
        assertEquals(2L, registry.current().generationId());
        assertEquals(secondBounds, registry.current().find(RIGID_KEY).orElseThrow().renderHandle().bounds());

        registry.publish(first);
        assertEquals(2L, registry.current().generationId());
        assertEquals(secondBounds, registry.current().find(RIGID_KEY).orElseThrow().renderHandle().bounds());
        assertTrue(first.isRetired());
    }

    private static void assertLoadedHandle(
            ModelRegistryGeneration generation,
            BlendModelKey key,
            Class<?> expectedRenderHandleType) {
        ModelHandle handle = generation.find(key).orElseThrow();
        assertTrue(handle instanceof LoadedModelHandle);
        assertFalse(handle.missing());
        assertTrue(expectedRenderHandleType.isInstance(handle.renderHandle()));
        assertEquals(GENERATION, handle.generationId());
        assertEquals(key, handle.key());
        assertEquals(GENERATION, handle.renderHandle().generation());
        assertEquals(key, handle.renderHandle().modelKey());
    }

    private static ModelAsset rigidAsset(MaterialDefinition material) {
        MeshPrimitive primitive = new MeshPrimitive(
                RIGID_SLOT,
                trianglePositions(),
                triangleNormals(),
                triangleUvs(),
                new int[] {0, 1, 2},
                null,
                null);
        return new ModelAsset(
                RIGID_KEY.resourceId(),
                RIGID_KEY.descriptorResourceId(),
                GENERATION,
                ModelProfile.RIGID_V1,
                1.0D,
                Map.of(RIGID_SLOT, material),
                null,
                List.of(new ModelNode(0, "RigidMesh", Transform.IDENTITY, List.of(), 0, -1, false)),
                List.of(0),
                List.of(new ModelPrimitive(0, 0, 0, primitive)),
                null,
                List.of(),
                new SocketTable(Map.of()),
                Bounds.fromPositions(primitive.positions()),
                List.of());
    }

    private static ModelAsset animatedRigidAsset(long generation, float translationX) {
        MeshPrimitive primitive = new MeshPrimitive(
                RIGID_SLOT,
                trianglePositions(),
                triangleNormals(),
                triangleUvs(),
                new int[] {0, 1, 2},
                null,
                null);
        AnimationClip clip = new AnimationClip("move-" + generation, List.of(new AnimationChannel(
                0,
                AnimationPath.TRANSLATION,
                Interpolation.LINEAR,
                new float[] {0.0F, 1.0F},
                new float[] {0.0F, 0.0F, 0.0F, translationX, 0.0F, 0.0F})));
        return new ModelAsset(
                RIGID_KEY.resourceId(),
                RIGID_KEY.descriptorResourceId(),
                generation,
                ModelProfile.RIGID_V1,
                1.0D,
                Map.of(RIGID_SLOT, material(MaterialDefinition.Mode.OPAQUE)),
                null,
                List.of(new ModelNode(0, "RigidMesh", Transform.IDENTITY, List.of(), 0, -1, false)),
                List.of(0),
                List.of(new ModelPrimitive(0, 0, 0, primitive)),
                null,
                List.of(clip),
                new SocketTable(Map.of()),
                Bounds.fromPositions(primitive.positions()),
                List.of());
    }

    private static ModelAsset skinnedAsset(MaterialDefinition material) {
        MeshPrimitive primitive = new MeshPrimitive(
                SKINNED_SLOT,
                trianglePositions(),
                triangleNormals(),
                triangleUvs(),
                new int[] {0, 1, 2},
                new int[] {
                    0, 0, 0, 0,
                    0, 0, 0, 0,
                    0, 0, 0, 0
                },
                new float[] {
                    1.0F, 0.0F, 0.0F, 0.0F,
                    1.0F, 0.0F, 0.0F, 0.0F,
                    1.0F, 0.0F, 0.0F, 0.0F
                });
        Skin skin = new Skin("ReloadSkin", 1, List.of(1), identityMatrices());
        return new ModelAsset(
                SKINNED_KEY.resourceId(),
                SKINNED_KEY.descriptorResourceId(),
                GENERATION,
                ModelProfile.SKINNED_V1,
                1.0D,
                Map.of(SKINNED_SLOT, material),
                null,
                List.of(
                        new ModelNode(0, "SkinnedMesh", Transform.IDENTITY, List.of(1), 0, 0, false),
                        new ModelNode(1, "Bone", Transform.IDENTITY, List.of(), -1, -1, false)),
                List.of(0),
                List.of(new ModelPrimitive(0, 0, 0, primitive)),
                new Skeleton(List.of(skin)),
                List.of(),
                new SocketTable(Map.of()),
                Bounds.fromPositions(primitive.positions()),
                List.of());
    }

    private static MaterialDefinition material(MaterialDefinition.Mode mode) {
        return new MaterialDefinition(TEXTURE, mode, false, false, null);
    }

    private static float[] trianglePositions() {
        return new float[] {0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F};
    }

    private static float[] triangleNormals() {
        return new float[] {0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F};
    }

    private static float[] triangleUvs() {
        return new float[] {0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F};
    }

    private static float[] identityMatrices() {
        return new float[] {
            1.0F, 0.0F, 0.0F, 0.0F,
            0.0F, 1.0F, 0.0F, 0.0F,
            0.0F, 0.0F, 1.0F, 0.0F,
            0.0F, 0.0F, 0.0F, 1.0F
        };
    }
}
