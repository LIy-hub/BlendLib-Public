package com.liy.blendlib.fabric.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
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
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.junit.jupiter.api.Test;

/** Layer ordering, all-eight collector submission, and prepared plan allocation coverage. */
class X6RenderLayerPlanTest {
    private static final BlendModelKey KEY = BlendModelKey.parse("x6_layer_test:actor/base");
    private static final long GENERATION = 73L;
    private static final BlendResourceId PRIMARY = id("primary");
    private static final BlendResourceId SECONDARY = id("secondary");
    private static final BlendResourceId BONE = id("bone");

    @Test
    void allEightLayerTypesResolveToFrozenExecutablePublicCollectorSemantics() {
        List<X6LayerEntry> entries = new ArrayList<>();
        int order = 0;
        for (X6LayerType type : X6LayerType.values()) {
            if (type != X6LayerType.PER_BONE_PART_TEXTURE) {
                entries.add(entry(type, id(type.name().toLowerCase()), order++, PRIMARY));
            }
        }
        X6PlanResult<X6RenderLayerPlan> result = X6RenderLayerPlanner.prepare(KEY, GENERATION, entries, geometry());
        assertTrue(result.publishable());
        Map<X6LayerType, X6ResolvedRenderLayer> byType = result.plan().orElseThrow().layers().stream()
                .collect(java.util.stream.Collectors.toMap(X6ResolvedRenderLayer::type, value -> value));
        assertEquals(7, byType.size());
        assertEquals(X6LayerPresentation.OUTLINE_SHELL, byType.get(X6LayerType.OUTLINE).presentation());
        assertEquals(X6LayerPresentation.SHADOW_FLATTEN, byType.get(X6LayerType.SHADOW).presentation());
        assertEquals(X6LayerPresentation.DAMAGE_FLASH, byType.get(X6LayerType.DAMAGE_FLASH).presentation());
        assertEquals(0xFFFF4040, byType.get(X6LayerType.DAMAGE_FLASH).presentationArgb());
        assertTrue(byType.get(X6LayerType.OUTLINE).preparedMaterial().isEmpty(), "inherited material is finalized from the variant draw later");
        assertEquals(X6BlendSemantic.INHERIT, byType.get(X6LayerType.OUTLINE).semantics().blend());
        assertEquals(X6TextureTarget.SECONDARY_TEXTURE, byType.get(X6LayerType.SECONDARY_TEXTURE).semantics().textureTarget());
        assertDiagnostic(X6RenderLayerPlanner.prepare(KEY, GENERATION, List.of(
                entry(X6LayerType.PER_BONE_PART_TEXTURE, id("static_per_bone"), 0, PRIMARY)), geometry()),
                X6DiagnosticCode.LAYER_TARGET_MISSING);
    }

    @Test
    void orderAndSemanticsAreFrozenByPhaseOrderAndIdBeforeSubmission() {
        List<X6LayerEntry> entries = List.of(
                entry(X6LayerType.GLOW, id("z_glow"), 5, PRIMARY),
                entry(X6LayerType.SECONDARY_TEXTURE, id("a_secondary"), 0, SECONDARY),
                entry(X6LayerType.OVERLAY, id("overlay"), 0, PRIMARY),
                entry(X6LayerType.DAMAGE_FLASH, id("damage"), 1, PRIMARY),
                entry(X6LayerType.OUTLINE, id("outline"), 0, PRIMARY),
                entry(X6LayerType.SHADOW, id("shadow"), 1, PRIMARY),
                entry(X6LayerType.ATTACHMENT, id("attachment"), 0, PRIMARY));
        X6PlanResult<X6RenderLayerPlan> result = X6RenderLayerPlanner.prepare(KEY, GENERATION, entries.reversed(), geometry());
        assertTrue(result.publishable());
        List<X6ResolvedRenderLayer> layers = result.plan().orElseThrow().layers();
        assertEquals(
                List.of(id("a_secondary"), id("z_glow"), id("overlay"), id("damage"), id("attachment"), id("outline"), id("shadow")),
                layers.stream().map(X6ResolvedRenderLayer::layerId).toList());
        assertEquals(X6BlendSemantic.INHERIT, layers.get(1).semantics().blend());
        assertEquals(X6CullingSemantic.INHERIT, layers.get(1).semantics().culling());
        assertEquals(X6LightSemantic.FULL_BRIGHT, layers.get(1).semantics().light());
    }

    @Test
    void duplicatePlacementMissingTargetsWrongGenerationAndWrongPhaseFailClosed() {
        X6LayerEntry first = entry(X6LayerType.GLOW, id("duplicate"), 0, PRIMARY);
        X6LayerEntry duplicateId = entry(X6LayerType.GLOW, id("duplicate"), 1, SECONDARY);
        assertDiagnostic(X6RenderLayerPlanner.prepare(KEY, GENERATION, List.of(first, duplicateId), geometry()), X6DiagnosticCode.LAYER_DUPLICATE);

        X6LayerEntry collision = entry(X6LayerType.SECONDARY_TEXTURE, id("collision"), 0, PRIMARY);
        assertDiagnostic(X6RenderLayerPlanner.prepare(KEY, GENERATION, List.of(first, collision), geometry()), X6DiagnosticCode.LAYER_CONFLICT);

        X6LayerEntry missing = new X6LayerEntry(
                id("missing"), X6LayerType.OVERLAY, X6RenderPhase.OVERLAY, 0,
                X6LayerTargetSelector.part(id("absent")), java.util.Optional.empty(), java.util.Optional.empty());
        assertDiagnostic(X6RenderLayerPlanner.prepare(KEY, GENERATION, List.of(missing), geometry()), X6DiagnosticCode.LAYER_TARGET_MISSING);

        X6LayerEntry wrongPhase = new X6LayerEntry(
                id("wrong_phase"), X6LayerType.GLOW, X6RenderPhase.OVERLAY, 0,
                X6LayerTargetSelector.part(PRIMARY), java.util.Optional.empty(), java.util.Optional.empty());
        assertDiagnostic(X6RenderLayerPlanner.prepare(KEY, GENERATION, List.of(wrongPhase), geometry()), X6DiagnosticCode.LAYER_CONFLICT);

        X6LayerEntry wrongBone = new X6LayerEntry(
                id("wrong_bone"), X6LayerType.PER_BONE_PART_TEXTURE, X6RenderPhase.POST_BASE, 0,
                X6LayerTargetSelector.partBone(PRIMARY, id("unknown_bone")),
                java.util.Optional.of(material("bone_texture")), java.util.Optional.empty());
        assertDiagnostic(X6RenderLayerPlanner.prepare(KEY, GENERATION, List.of(wrongBone), geometry()), X6DiagnosticCode.LAYER_TARGET_MISSING);

        ModelRenderSnapshot stale = snapshot(BlendModelKey.parse("x6_layer_test:child/stale"), GENERATION + 1L);
        X6LayerEntry staleAttachment = new X6LayerEntry(
                id("stale_attachment"), X6LayerType.ATTACHMENT, X6RenderPhase.ATTACHMENT, 0,
                X6LayerTargetSelector.wholeModel(), java.util.Optional.empty(), java.util.Optional.of(stale));
        assertDiagnostic(X6RenderLayerPlanner.prepare(KEY, GENERATION, List.of(staleAttachment), geometry()), X6DiagnosticCode.GENERATION_MISMATCH);
    }

    @Test
    void planToCollectorExecutesEveryNonAttachmentLayerAndUsesPreFrozenDrawLookup() throws IOException {
        BoundGeometry bound = boundGeometry();
        X6PreparedGeometryCatalog geometry = bound.geometry();
        X6MaterialPlan materials = materialPlan(geometry);
        X6VariantApplicationPlan variants = variants(geometry, materials);
        List<X6LayerEntry> entries = new ArrayList<>();
        int order = 0;
        for (X6LayerType type : X6LayerType.values()) {
            if (type != X6LayerType.PER_BONE_PART_TEXTURE) {
                entries.add(entry(type, id("collect_" + type.name().toLowerCase()), order++, PRIMARY));
            }
        }
        X6RenderLayerPlan layers = X6RenderLayerPlanner.prepare(KEY, GENERATION, entries, geometry).plan().orElseThrow();
        X6MaterialProviderGeneration providers = X6MaterialProviderGeneration.prepareAndPublish(KEY, GENERATION, List.of(), List.of());
        X6TestLifecycleDrainDispatcher lifecycleOwner = new X6TestLifecycleDrainDispatcher();
        X6PreparedRenderPlan plan = X6PreparedRenderPlanFactory.prepare(
                variants, layers, materials, geometry, providers, bound.snapshot(), lifecycleOwner).plan().orElseThrow();
        try {
            RecordingSubmitNodeStorage collector = new RecordingSubmitNodeStorage();
            AtomicReference<ModelRenderSnapshot> delegated = new AtomicReference<>();
            X6PlanSubmitter.submit(
                    plan,
                    bound.snapshot(),
                    new RenderSubmissionContext(new PoseStack(), collector),
                    (attachment, context) -> delegated.set(attachment));
            assertEquals(2, plan.baseDraws().size());
            assertEquals(7, plan.layerSubmissions().size(), "all static-safe planned layers, including attachment, are frozen");
            assertTrue(collector.getSubmitsPerOrder().get(0).wasUsed(),
                    "the real public collector received the plan's base and non-attachment layer callbacks");
            assertEquals(8, collector.captures.size(), "two base draws and every six non-attachment static-safe layer invoke the collector");
            assertEquals(0xFFFF4040, collector.captures.get(5).colors.getFirst(),
                    "DAMAGE_FLASH applies its frozen red presentation multiplier at collector emission");
            assertEquals(0xFF101010, collector.captures.get(6).colors.getFirst(),
                    "OUTLINE applies its frozen dark presentation multiplier at collector emission");
            assertEquals(0x66000000, collector.captures.get(7).colors.getFirst(),
                    "SHADOW applies its frozen translucent presentation multiplier at collector emission");
            assertFalse(collector.captures.get(6).positions.equals(collector.captures.get(4).positions),
                    "OUTLINE shell offsets emitted geometry rather than reusing the overlay vertices");
            assertFalse(collector.captures.get(6).renderType.equals(collector.captures.get(4).renderType),
                    "OUTLINE reaches the collector on its frozen cutout route, not the overlay route");
            assertFalse(collector.captures.get(7).renderType.equals(collector.captures.get(4).renderType),
                    "SHADOW reaches the collector on its frozen translucent route, not the overlay route");
            assertEquals(BlendModelKey.parse("x6_layer_test:child/attachment"), delegated.get().handle().modelKey());
            assertEquals(materials, plan.materials());
            ModelRenderSnapshot laterCulled = new ModelRenderSnapshot(
                    bound.snapshot().handle(),
                    bound.snapshot().rootTransform(),
                    bound.snapshot().packedLight(),
                    bound.snapshot().packedOverlay(),
                    bound.snapshot().tintArgb(),
                    RenderVisibility.CULLED,
                    new CullingMetadata(bound.snapshot().culling().worldBounds(), false));
            X6PlanSubmitter.submit(
                    plan,
                    laterCulled,
                    new RenderSubmissionContext(new PoseStack(), collector),
                    (attachment, context) -> delegated.set(attachment));
            assertEquals(8, collector.captures.size(),
                    "a later compatible snapshot keeps its own frozen visibility instead of inheriting the plan binding frame");
            assertThrows(IllegalArgumentException.class,
                    () -> X6PlanSubmitter.submit(
                            plan,
                        snapshot(KEY, GENERATION + 1L),
                            new RenderSubmissionContext(new PoseStack(), new SubmitNodeStorage()),
                            (attachment, context) -> assertNull(attachment)));
        } finally {
            plan.close();
            lifecycleOwner.runAll();
            providers.close();
        }
        assertThrows(IllegalStateException.class,
                () -> X6PlanSubmitter.submit(
                        plan,
                        bound.snapshot(),
                        new RenderSubmissionContext(new PoseStack(), new SubmitNodeStorage()),
                        (attachment, context) -> assertNull(attachment)));

        String source = Files.readString(renderSource("X6PlanSubmitter.java"));
        for (String forbidden : List.of(
                "MaterialProvider", "CapabilityRegistry", "parseForPrepare", "ResourceManager", "java.nio.file.", "java.io.",
                "GlbReader", "StrictJsonParser", "org.lwjgl", "RenderSystem", "ClientModelLookup", "ClientGenerationLeaseBinding",
                "X7GenerationPerformancePlan", "X7CullingPolicy", "X7BudgetPolicy", "X7LodPolicy", "X7AnimationWorkPolicy",
                "new LinkedHashMap", "new Minecraft2612StaticRigidRenderBackend", "new Quaternionf")) {
            assertFalse(source.contains(forbidden), forbidden);
        }
        assertTrue(source.contains("permitsFrozenCpuRoute"));
        assertTrue(source.contains("QUATERNION_SCRATCH"));
        assertTrue(source.contains("FrozenGeometryRenderer"), "one fixed deferred renderer object is created per collector submission");
        assertFalse(source.contains("(pose, consumer) ->"), "submit must not create a nested collector lambda");
        assertFalse(source.contains(".emit(("), "submit must not create a nested capturing VertexSink lambda");
    }

    private static void assertDiagnostic(X6PlanResult<?> result, X6DiagnosticCode code) {
        assertFalse(result.publishable());
        assertTrue(result.diagnostics().stream().anyMatch(value -> value.code() == code), () -> "expected " + code);
    }

    private static X6LayerEntry entry(X6LayerType type, BlendResourceId layerId, int order, BlendResourceId target) {
        if (type == X6LayerType.ATTACHMENT) {
            return new X6LayerEntry(
                    layerId,
                    type,
                    X6RenderPhase.ATTACHMENT,
                    order,
                    X6LayerTargetSelector.wholeModel(),
                    java.util.Optional.empty(),
                    java.util.Optional.of(snapshot(BlendModelKey.parse("x6_layer_test:child/attachment"), GENERATION)));
        }
        java.util.Optional<RenderMaterial> material = switch (type) {
            case SECONDARY_TEXTURE, PER_BONE_PART_TEXTURE -> java.util.Optional.of(material("layer_" + type.name().toLowerCase()));
            default -> java.util.Optional.empty();
        };
        X6LayerTargetSelector selector = type == X6LayerType.PER_BONE_PART_TEXTURE
                ? X6LayerTargetSelector.partBone(target, BONE)
                : X6LayerTargetSelector.part(target);
        return new X6LayerEntry(layerId, type, phase(type), order, selector, material, java.util.Optional.empty());
    }

    private static X6RenderPhase phase(X6LayerType type) {
        return switch (type) {
            case GLOW, SECONDARY_TEXTURE, PER_BONE_PART_TEXTURE -> X6RenderPhase.POST_BASE;
            case OVERLAY, DAMAGE_FLASH -> X6RenderPhase.OVERLAY;
            case ATTACHMENT -> X6RenderPhase.ATTACHMENT;
            case OUTLINE, SHADOW -> X6RenderPhase.PRESENTATION;
        };
    }

    private static X6PreparedGeometryCatalog geometry() {
        return new X6PreparedGeometryCatalog(
                KEY,
                GENERATION,
                Map.of(
                        PRIMARY, primitive(0, material("base_primary")),
                        SECONDARY, primitive(0, material("base_secondary"))),
                Map.of(BONE, 0));
    }

    private static BoundGeometry boundGeometry() {
        X6PreparedGeometryCatalog geometry = geometry();
        X6TestRenderHandle handle = new X6TestRenderHandle(
                KEY, GENERATION, List.copyOf(geometry.primitives().values()), Map.of(0, Transform.IDENTITY), false);
        ModelRenderSnapshot snapshot = new ModelRenderSnapshot(
                handle,
                Transform.IDENTITY,
                Minecraft2612StaticRigidRenderBackend.FULL_BRIGHT_PACKED_LIGHT,
                0,
                0xFFFFFFFF,
                RenderVisibility.VISIBLE,
                new CullingMetadata(handle.bounds(), true));
        return new BoundGeometry(geometry, snapshot);
    }

    private static X6MaterialPlan materialPlan(X6PreparedGeometryCatalog geometry) {
        Map<BlendResourceId, X6MaterialIntent> intents = new LinkedHashMap<>();
        for (BlendResourceId partId : geometry.bindings().keySet()) {
            intents.put(partId, new X6MaterialIntent(id("textures/plan_" + partId.path() + ".png"), X6MaterialMode.OPAQUE, false, false, null));
        }
        return X6MaterialPlan.prepare(KEY, GENERATION, intents).plan().orElseThrow();
    }

    private static X6VariantApplicationPlan variants(X6PreparedGeometryCatalog geometry, X6MaterialPlan materials) {
        List<X6DrawPrimitive> draws = geometry.bindings().entrySet().stream()
                .map(entry -> new X6DrawPrimitive(entry.getKey(), entry.getValue(), materials.material(entry.getKey()), 0xFFFFFFFF))
                .toList();
        return new X6VariantApplicationPlan(KEY, GENERATION, draws, List.of());
    }

    private static PreparedRenderPrimitive primitive(int node, RenderMaterial material) {
        return new PreparedRenderPrimitive(node, StaticGeometry.of(
                new float[] {0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f},
                new float[] {0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f},
                new float[] {0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f},
                new int[] {0, 1, 2}), material);
    }

    private static RenderMaterial material(String name) {
        return new RenderMaterial(id("textures/" + name + ".png"), RenderLayer.SOLID, false, false, 0xFFFFFFFF, false);
    }

    private static ModelRenderSnapshot snapshot(BlendModelKey key, long generation) {
        MissingModelRenderHandle handle = new MissingModelRenderHandle(key, generation);
        return new ModelRenderSnapshot(
                handle,
                Transform.IDENTITY,
                Minecraft2612StaticRigidRenderBackend.FULL_BRIGHT_PACKED_LIGHT,
                0,
                0xFFFFFFFF,
                RenderVisibility.VISIBLE,
                new CullingMetadata(handle.bounds(), true));
    }

    private static BlendResourceId id(String path) {
        return BlendResourceId.parse("x6_layer_test:" + path);
    }

    private static Path renderSource(String name) {
        return Path.of(System.getProperty("blendlib.projectDir"), "src", "client", "java", "com", "liy", "blendlib",
                "fabric", "client", "render", name);
    }

    /** Calls each public collector callback once in test while retaining the real storage behavior. */
    private static final class RecordingSubmitNodeStorage extends SubmitNodeStorage {
        private final List<CapturedGeometry> captures = new ArrayList<>();

        @Override
        public void submitCustomGeometry(
                PoseStack poseStack,
                RenderType renderType,
                SubmitNodeCollector.CustomGeometryRenderer renderer) {
            CapturingVertexConsumer consumer = new CapturingVertexConsumer();
            renderer.render(poseStack.last(), consumer);
            captures.add(new CapturedGeometry(renderType, consumer.colors, consumer.positions));
            super.submitCustomGeometry(poseStack, renderType, renderer);
        }
    }

    private static final class CapturingVertexConsumer implements VertexConsumer {
        private final List<Integer> colors = new ArrayList<>();
        private final List<Float> positions = new ArrayList<>();

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            positions.add(x);
            positions.add(y);
            positions.add(z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            colors.add(alpha << 24 | red << 16 | green << 8 | blue);
            return this;
        }

        @Override
        public VertexConsumer setColor(int argb) {
            colors.add(argb);
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

    private record CapturedGeometry(RenderType renderType, List<Integer> colors, List<Float> positions) {
    }

    private record BoundGeometry(X6PreparedGeometryCatalog geometry, ModelRenderSnapshot snapshot) {
    }
}
