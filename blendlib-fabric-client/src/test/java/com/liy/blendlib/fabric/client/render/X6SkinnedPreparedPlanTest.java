package com.liy.blendlib.fabric.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.runtime.CpuSkinnedMesh;
import com.liy.blendlib.core.animation.runtime.CpuSkinner;
import com.liy.blendlib.core.animation.runtime.LocalPose;
import com.liy.blendlib.core.animation.runtime.NodePalette;
import com.liy.blendlib.core.animation.runtime.SkinPalette;
import com.liy.blendlib.core.descriptor.MaterialDefinition;
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
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.junit.jupiter.api.Test;

/** R2 regression coverage for exact captured skinned geometry and canonical per-bone subsets. */
class X6SkinnedPreparedPlanTest {
    private static final BlendModelKey KEY = BlendModelKey.parse("x6_skinned_test:actor/base");
    private static final long GENERATION = 104L;
    private static final BlendResourceId PART = id("mesh");
    private static final BlendResourceId BONE_A = id("bone_a");
    private static final BlendResourceId BONE_B = id("bone_b");
    private static final BlendResourceId ZERO_BONE = id("zero_bone");

    @Test
    void perBoneLayersFreezeCanonicalBoneTargetsAndEmitOnlyInfluencedSharedGeometry() {
        Fixture fixture = fixture();
        PreparedSkinnedRenderPrimitive primitive = fixture.handle().skinnedPrimitives().getFirst();
        X6PreparedGeometryCatalog geometry = geometry(primitive, 0, bones());
        X6MaterialPlan materials = materials(geometry);
        X6VariantApplicationPlan variants = variants(geometry, materials);
        X6RenderLayerPlan layers = X6RenderLayerPlanner.prepare(KEY, GENERATION, List.of(
                perBone("bone_a", 0, BONE_A),
                perBone("bone_b", 1, BONE_B)), geometry).plan().orElseThrow();
        ModelRenderSnapshot snapshot = snapshot(fixture);
        X6MaterialProviderGeneration providers = X6MaterialProviderGeneration.prepareAndPublish(KEY, GENERATION, List.of(), List.of());
        X6TestLifecycleDrainDispatcher lifecycleOwner = new X6TestLifecycleDrainDispatcher();
        X6PreparedRenderPlan plan = X6PreparedRenderPlanFactory.prepare(
                variants, layers, materials, geometry, providers, snapshot, lifecycleOwner).plan().orElseThrow();
        try {
            Map<BlendResourceId, X6PreparedLayerSubmission> byLayer = plan.layerSubmissions().stream()
                    .collect(java.util.stream.Collectors.toMap(value -> value.layer().layerId(), value -> value));
            X6BoneInfluenceSubset first = byLayer.get(id("bone_a")).boneSubset().orElseThrow();
            X6BoneInfluenceSubset second = byLayer.get(id("bone_b")).boneSubset().orElseThrow();
            assertEquals(BONE_A, first.canonicalBoneId());
            assertEquals(1, first.boneNodeIndex(), "canonical node 1 is not the primitive node 0");
            assertEquals(2, second.boneNodeIndex());
            assertEquals(2, first.triangleCount(), "joint 0 controls its own and mixed-weight triangle only");
            assertEquals(2, second.triangleCount(), "joint 1 controls its own and mixed-weight triangle only");
            assertSame(primitive, first.primitive(), "subset retains an immutable prepared geometry reference");
            assertSame(primitive, second.primitive(), "subset never copies a whole skinned mesh");
            assertEquals(variants.draws().getFirst().material(), byLayer.get(id("bone_a")).material(),
                    "a missing explicit per-bone material inherits the final variant material");

            RecordingSubmitNodeStorage collector = new RecordingSubmitNodeStorage();
            X6PlanSubmitter.submit(plan, snapshot, new RenderSubmissionContext(new PoseStack(), collector), (child, context) -> {
                throw new AssertionError("fixture has no attachments");
            });
            assertEquals(3, collector.captures.size());
            assertEquals(16, collector.captures.get(0).vertexCount(), "base draw emits all four source triangles");
            assertEquals(8, collector.captures.get(1).vertexCount(), "bone A layer emits only two influenced triangles");
            assertEquals(8, collector.captures.get(2).vertexCount(), "bone B layer emits only two influenced triangles");
            assertEquals(RenderLayer.CUTOUT, plan.baseDraws().getFirst().draw().material().layer());
        } finally {
            plan.close();
            lifecycleOwner.runAll();
            providers.close();
        }
    }

    @Test
    void zeroInfluenceInvalidBoneAndStaticPerBoneTargetsFailClosedDuringPreparation() {
        Fixture fixture = fixture();
        PreparedSkinnedRenderPrimitive primitive = fixture.handle().skinnedPrimitives().getFirst();
        X6PreparedGeometryCatalog geometry = geometry(primitive, 0, Map.of(
                BONE_A, new X6SkinnedBoneBinding(1, 0, 0),
                ZERO_BONE, new X6SkinnedBoneBinding(4, 0, 3)));
        X6MaterialPlan materials = materials(geometry);
        X6VariantApplicationPlan variants = variants(geometry, materials);
        X6RenderLayerPlan zeroLayer = X6RenderLayerPlanner.prepare(KEY, GENERATION, List.of(
                perBone("zero", 0, ZERO_BONE)), geometry).plan().orElseThrow();
        X6MaterialProviderGeneration providers = X6MaterialProviderGeneration.prepareAndPublish(KEY, GENERATION, List.of(), List.of());
        X6TestLifecycleDrainDispatcher lifecycleOwner = new X6TestLifecycleDrainDispatcher();
        try {
            assertDiagnostic(X6PreparedRenderPlanFactory.prepare(
                    variants, zeroLayer, materials, geometry, providers, snapshot(fixture), lifecycleOwner), X6DiagnosticCode.LAYER_TARGET_MISSING);
            assertEquals(1, lifecycleOwner.cancelCount(), "post-pin assembly failure must revoke its pre-admitted owner bridge");
        } finally {
            providers.close();
        }

        assertDiagnostic(X6RenderLayerPlanner.prepare(KEY, GENERATION, List.of(
                perBone("unknown", 0, id("unknown"))), geometry), X6DiagnosticCode.LAYER_TARGET_MISSING);

        PreparedRenderPrimitive staticPrimitive = new PreparedRenderPrimitive(0, StaticGeometry.of(
                new float[] {0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f},
                new float[] {0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f},
                new float[] {0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f}, new int[] {0, 1, 2}),
                new RenderMaterial(id("textures/static.png"), RenderLayer.SOLID, false, false, 0xFFFFFFFF, false));
        X6PreparedGeometryCatalog staticGeometry = new X6PreparedGeometryCatalog(
                KEY, GENERATION, Map.of(PART, staticPrimitive), Map.of(BONE_A, 0));
        assertDiagnostic(X6RenderLayerPlanner.prepare(KEY, GENERATION, List.of(
                perBone("static", 0, BONE_A)), staticGeometry), X6DiagnosticCode.LAYER_TARGET_MISSING);
    }

    @Test
    void wrongCapturedMeshIndexFailsAtFactoryRatherThanDuringCollectorCallback() {
        Fixture fixture = fixture();
        PreparedSkinnedRenderPrimitive primitive = fixture.handle().skinnedPrimitives().getFirst();
        X6PreparedGeometryCatalog geometry = geometry(primitive, 1, bones());
        X6MaterialPlan materials = materials(geometry);
        X6VariantApplicationPlan variants = variants(geometry, materials);
        X6RenderLayerPlan layers = X6RenderLayerPlanner.prepare(KEY, GENERATION, List.of(), geometry).plan().orElseThrow();
        X6MaterialProviderGeneration providers = X6MaterialProviderGeneration.prepareAndPublish(KEY, GENERATION, List.of(), List.of());
        try {
            assertDiagnostic(X6PreparedRenderPlanFactory.prepare(
                    variants, layers, materials, geometry, providers, snapshot(fixture)), X6DiagnosticCode.GEOMETRY_MISMATCH);
        } finally {
            providers.close();
        }
    }

    @Test
    void foreignSameGenerationSkinnedCaptureIsRejectedBeforeAnyDeferredCallbackCanObserveItsPayload() {
        Fixture first = fixture(0.0f);
        Fixture foreign = fixture(99.0f);
        SkinnedRenderSnapshot foreignCapture = SkinnedRenderSnapshot.capture(foreign.handle(), List.of(foreign.output()));
        assertEquals(first.handle().modelKey(), foreign.handle().modelKey());
        assertEquals(first.handle().generation(), foreign.handle().generation());
        assertEquals(1, foreignCapture.meshCount());

        PreparedSkinnedRenderPrimitive primitive = first.handle().skinnedPrimitives().getFirst();
        X6PreparedGeometryCatalog geometry = geometry(primitive, 0, bones());
        X6MaterialPlan materials = materials(geometry);
        X6VariantApplicationPlan variants = variants(geometry, materials);
        X6RenderLayerPlan layers = X6RenderLayerPlanner.prepare(KEY, GENERATION, List.of(), geometry).plan().orElseThrow();
        X6MaterialProviderGeneration providers = X6MaterialProviderGeneration.prepareAndPublish(KEY, GENERATION, List.of(), List.of());
        X6TestLifecycleDrainDispatcher lifecycleOwner = new X6TestLifecycleDrainDispatcher();
        X6PreparedRenderPlan plan = X6PreparedRenderPlanFactory.prepare(
                variants, layers, materials, geometry, providers, snapshot(first), lifecycleOwner).plan().orElseThrow();
        try {
            RecordingSubmitNodeStorage collector = new RecordingSubmitNodeStorage();
            try {
                ModelRenderSnapshot mixed = ModelRenderSnapshot.skinned(
                        first.handle(),
                        Transform.IDENTITY,
                        0x000A000B,
                        7,
                        0xFF80FF80,
                        RenderVisibility.VISIBLE,
                        new CullingMetadata(first.handle().bounds(), true),
                        foreignCapture);
                X6PlanSubmitter.submit(plan, mixed, new RenderSubmissionContext(new PoseStack(), collector), (child, context) -> {
                    throw new AssertionError("fixture has no attachments");
                });
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("exact source handle"));
                assertTrue(collector.captures.isEmpty(), "identity rejection must happen before any deferred callback");
                return;
            }
            assertEquals(1, collector.captures.size(), "old compatibility admitted one foreign base callback");
            assertEquals(99.0f, collector.captures.getFirst().firstVertexX(),
                    "old compatibility exposed H2's foreign payload through H1's prepared plan");
            fail("foreign skinned capture was not rejected before callback; observed first vertex x="
                    + collector.captures.getFirst().firstVertexX());
        } finally {
            plan.close();
            lifecycleOwner.runAll();
            providers.close();
        }
    }

    @Test
    void factoryFencesCanonicalJointNodeAndRangeBeforePinningAndSubmitKeepsExactHandleFence() throws IOException {
        Fixture fixture = fixture();
        PreparedSkinnedRenderPrimitive primitive = fixture.handle().skinnedPrimitives().getFirst();
        X6MaterialProviderGeneration providers = X6MaterialProviderGeneration.prepareAndPublish(KEY, GENERATION, List.of(), List.of());
        try {
            X6PreparedGeometryCatalog wrongNode = geometry(primitive, 0, Map.of(
                    BONE_A, new X6SkinnedBoneBinding(1, 0, 1)));
            X6MaterialPlan wrongNodeMaterials = materials(wrongNode);
            X6VariantApplicationPlan wrongNodeVariants = variants(wrongNode, wrongNodeMaterials);
            X6RenderLayerPlan wrongNodeLayers = X6RenderLayerPlanner.prepare(
                    KEY, GENERATION, List.of(), wrongNode).plan().orElseThrow();
            assertDiagnostic(X6PreparedRenderPlanFactory.prepare(
                    wrongNodeVariants, wrongNodeLayers, wrongNodeMaterials, wrongNode, providers, snapshot(fixture)),
                    X6DiagnosticCode.GEOMETRY_MISMATCH);

            X6PreparedGeometryCatalog outOfRangeJoint = geometry(primitive, 0, Map.of(
                    BONE_A, new X6SkinnedBoneBinding(1, 0, 4)));
            X6MaterialPlan outOfRangeMaterials = materials(outOfRangeJoint);
            X6VariantApplicationPlan outOfRangeVariants = variants(outOfRangeJoint, outOfRangeMaterials);
            X6RenderLayerPlan outOfRangeLayers = X6RenderLayerPlanner.prepare(
                    KEY, GENERATION, List.of(), outOfRangeJoint).plan().orElseThrow();
            assertDiagnostic(X6PreparedRenderPlanFactory.prepare(
                    outOfRangeVariants, outOfRangeLayers, outOfRangeMaterials, outOfRangeJoint, providers, snapshot(fixture)),
                    X6DiagnosticCode.GEOMETRY_MISMATCH);

            X6PreparedGeometryCatalog correct = geometry(primitive, 0, bones());
            X6MaterialPlan correctMaterials = materials(correct);
            X6VariantApplicationPlan correctVariants = variants(correct, correctMaterials);
            X6RenderLayerPlan correctLayers = X6RenderLayerPlanner.prepare(KEY, GENERATION, List.of(), correct).plan().orElseThrow();
            X6TestLifecycleDrainDispatcher lifecycleOwner = new X6TestLifecycleDrainDispatcher();
            X6PreparedRenderPlan plan = X6PreparedRenderPlanFactory.prepare(
                    correctVariants, correctLayers, correctMaterials, correct, providers, snapshot(fixture), lifecycleOwner).plan().orElseThrow();
            try {
                IllegalArgumentException foreignHandle = assertThrows(
                        IllegalArgumentException.class,
                        () -> plan.requireCompatible(snapshot(fixture())));
                assertTrue(foreignHandle.getMessage().contains(X6DiagnosticCode.GEOMETRY_MISMATCH.code()));
            } finally {
                plan.close();
                lifecycleOwner.runAll();
            }
        } finally {
            providers.close();
        }

        String planSource = Files.readString(renderSource("X6PreparedRenderPlan.java"));
        String catalogSource = Files.readString(renderSource("X6PreparedGeometryCatalog.java"));
        assertTrue(planSource.contains("snapshot.handle() != boundHandle"));
        assertFalse(planSource.contains("geometry.requireCompatible(snapshot)"),
                "submit compatibility must not trigger a full catalog scan after factory binding");
        assertFalse(catalogSource.contains("void requireCompatible(ModelRenderSnapshot snapshot)"),
                "the catalog must not retain a reusable full-validation submit entry point");
    }

    private static X6PreparedGeometryCatalog geometry(
            PreparedSkinnedRenderPrimitive primitive,
            int meshIndex,
            Map<BlendResourceId, X6SkinnedBoneBinding> bones) {
        return X6PreparedGeometryCatalog.skinned(KEY, GENERATION, Map.of(PART, primitive), Map.of(PART, meshIndex), bones);
    }

    private static Map<BlendResourceId, X6SkinnedBoneBinding> bones() {
        return Map.of(
                BONE_A, new X6SkinnedBoneBinding(1, 0, 0),
                BONE_B, new X6SkinnedBoneBinding(2, 0, 1));
    }

    private static X6MaterialPlan materials(X6PreparedGeometryCatalog geometry) {
        return X6MaterialPlan.prepare(KEY, GENERATION, Map.of(
                PART, new X6MaterialIntent(id("textures/variant_material.png"), X6MaterialMode.CUTOUT, false, false, null)))
                .plan().orElseThrow();
    }

    private static X6VariantApplicationPlan variants(X6PreparedGeometryCatalog geometry, X6MaterialPlan materials) {
        return new X6VariantApplicationPlan(
                KEY, GENERATION, List.of(new X6DrawPrimitive(PART, geometry.binding(PART), materials.material(PART), 0x80FFFFFF)), List.of());
    }

    private static X6LayerEntry perBone(String name, int order, BlendResourceId bone) {
        return new X6LayerEntry(
                id(name),
                X6LayerType.PER_BONE_PART_TEXTURE,
                X6RenderPhase.POST_BASE,
                order,
                X6LayerTargetSelector.partBone(PART, bone),
                Optional.empty(),
                Optional.empty());
    }

    private static ModelRenderSnapshot snapshot(Fixture fixture) {
        return ModelRenderSnapshot.skinned(
                fixture.handle(),
                Transform.IDENTITY,
                0x000A000B,
                7,
                0xFF80FF80,
                RenderVisibility.VISIBLE,
                new CullingMetadata(fixture.handle().bounds(), true),
                SkinnedRenderSnapshot.capture(fixture.handle(), List.of(fixture.output())));
    }

    private static void assertDiagnostic(X6PlanResult<?> result, X6DiagnosticCode code) {
        assertFalse(result.publishable());
        assertTrue(result.diagnostics().stream().anyMatch(value -> value.code() == code), () -> "expected " + code);
    }

    private static Fixture fixture() {
        return fixture(0.0f);
    }

    private static Fixture fixture(float xOffset) {
        MeshPrimitive geometry = mesh(xOffset);
        Skin skin = new Skin("FixtureSkin", 1, List.of(1, 2, 3, 4), identityMatrices(4));
        List<ModelNode> nodes = List.of(
                new ModelNode(0, "Mesh", Transform.IDENTITY, List.of(1), 0, 0, false),
                new ModelNode(1, "BoneA", Transform.IDENTITY, List.of(2), -1, -1, false),
                new ModelNode(2, "BoneB", Transform.IDENTITY, List.of(3), -1, -1, false),
                new ModelNode(3, "ThirdBone", Transform.IDENTITY, List.of(4), -1, -1, false),
                new ModelNode(4, "ZeroInfluenceBone", Transform.IDENTITY, List.of(), -1, -1, false));
        MaterialDefinition material = new MaterialDefinition(id("textures/skinned.png"), MaterialDefinition.Mode.CUTOUT, false, false, null);
        ModelAsset asset = new ModelAsset(
                KEY.resourceId(),
                KEY.descriptorResourceId(),
                GENERATION,
                ModelProfile.SKINNED_V1,
                1.0d,
                Map.of("SkinSurface", material),
                null,
                nodes,
                List.of(0),
                List.of(new ModelPrimitive(0, 0, 0, geometry)),
                new Skeleton(List.of(skin)),
                List.of(),
                new SocketTable(Map.of()),
                Bounds.fromPositions(geometry.positions()),
                List.of());
        SkinnedRenderHandle handle = SkinnedRenderHandle.prepare(KEY, asset);
        LocalPose pose = new LocalPose(Map.of(
                0, Transform.IDENTITY, 1, Transform.IDENTITY, 2, Transform.IDENTITY, 3, Transform.IDENTITY, 4, Transform.IDENTITY));
        SkinPalette palette = SkinPalette.from(skin, NodePalette.from(pose, nodes));
        return new Fixture(handle, CpuSkinner.skin(handle.skinnedPrimitives().getFirst().geometry(), palette));
    }

    private static MeshPrimitive mesh(float xOffset) {
        float[] positions = new float[12 * 3];
        float[] normals = new float[12 * 3];
        float[] texCoords = new float[12 * 2];
        int[] joints = new int[12 * 4];
        float[] weights = new float[12 * 4];
        for (int vertex = 0; vertex < 12; vertex++) {
            int position = vertex * 3;
            positions[position] = xOffset + vertex;
            positions[position + 1] = vertex % 3;
            normals[position + 2] = 1.0f;
            texCoords[vertex * 2] = vertex / 12.0f;
        }
        for (int vertex = 0; vertex < 3; vertex++) {
            weights[vertex * 4] = 1.0f;
        }
        for (int vertex = 3; vertex < 6; vertex++) {
            joints[vertex * 4] = 1;
            weights[vertex * 4] = 1.0f;
        }
        for (int vertex = 6; vertex < 9; vertex++) {
            joints[vertex * 4] = 2;
            weights[vertex * 4] = 1.0f;
        }
        for (int vertex = 9; vertex < 12; vertex++) {
            joints[vertex * 4] = 0;
            joints[vertex * 4 + 1] = 1;
            weights[vertex * 4] = 0.5f;
            weights[vertex * 4 + 1] = 0.5f;
        }
        return new MeshPrimitive(
                "SkinSurface", positions, normals, texCoords,
                new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11}, joints, weights);
    }

    private static float[] identityMatrices(int count) {
        float[] result = new float[count * 16];
        for (int index = 0; index < count; index++) {
            int offset = index * 16;
            result[offset] = 1.0f;
            result[offset + 5] = 1.0f;
            result[offset + 10] = 1.0f;
            result[offset + 15] = 1.0f;
        }
        return result;
    }

    private static BlendResourceId id(String path) {
        return BlendResourceId.parse("x6_skinned_test:" + path);
    }

    private static Path renderSource(String name) {
        return Path.of(System.getProperty("blendlib.projectDir"), "src", "client", "java", "com", "liy", "blendlib",
                "fabric", "client", "render", name);
    }

    private record Fixture(SkinnedRenderHandle handle, CpuSkinnedMesh output) {
    }

    private static final class RecordingSubmitNodeStorage extends SubmitNodeStorage {
        private final List<CapturedGeometry> captures = new ArrayList<>();

        @Override
        public void submitCustomGeometry(
                PoseStack poseStack,
                RenderType renderType,
                SubmitNodeCollector.CustomGeometryRenderer renderer) {
            CountingVertexConsumer consumer = new CountingVertexConsumer();
            renderer.render(poseStack.last(), consumer);
            captures.add(new CapturedGeometry(renderType, consumer.vertexCount, consumer.firstVertexX));
            super.submitCustomGeometry(poseStack, renderType, renderer);
        }
    }

    private static final class CountingVertexConsumer implements VertexConsumer {
        private int vertexCount;
        private float firstVertexX = Float.NaN;

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            if (vertexCount == 0) {
                firstVertexX = x;
            }
            vertexCount++;
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            return this;
        }

        @Override
        public VertexConsumer setColor(int argb) {
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            return this;
        }
    }

    private record CapturedGeometry(RenderType renderType, int vertexCount, float firstVertexX) {
    }
}
