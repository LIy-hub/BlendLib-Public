package com.liy.blendlib.fabric.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.spi.experimental.CapabilityFallback;
import com.liy.blendlib.spi.experimental.CapabilityOffer;
import com.liy.blendlib.spi.experimental.CapabilityRequest;
import com.liy.blendlib.spi.experimental.CapabilitySelectionOutcome;
import com.liy.blendlib.spi.experimental.CapabilityVersion;
import com.liy.blendlib.spi.experimental.CapabilityVersionRange;
import com.liy.blendlib.spi.experimental.MaterialProvider;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Precise second-review regressions for final materials, bound geometry, and provider containment. */
class X6R2RegressionTest {
    private static final BlendModelKey KEY = BlendModelKey.parse("x6_r2:actor/base");
    private static final long GENERATION = 211L;
    private static final BlendResourceId PART = id("part");
    private static final BlendResourceId REPLACEMENT = id("replacement");
    private static final BlendResourceId CAPABILITY = id("standard_material");
    private static final CapabilityVersionRange VERSION_RANGE = CapabilityVersion.CURRENT_PROTOCOL_RANGE;

    @Test
    void finalVariantDrawControlsEveryInheritedLayerAndExplicitLayerMaterialWins() {
        PreparedRenderPrimitive base = primitive(0, material("base", RenderLayer.SOLID, false, false));
        PreparedRenderPrimitive replacement = primitive(0, material("replacement", RenderLayer.SOLID, false, false));
        X6PreparedGeometryCatalog geometry = new X6PreparedGeometryCatalog(
                KEY, GENERATION, Map.of(PART, base, REPLACEMENT, replacement));
        X6TestRenderHandle handle = handle(List.of(base, replacement), Map.of(0, Transform.IDENTITY), false);
        ModelRenderSnapshot binding = snapshot(handle);
        X6MaterialPlan materials = X6MaterialPlan.prepare(KEY, GENERATION, Map.of(
                PART, new X6MaterialIntent(id("textures/base_plan.png"), X6MaterialMode.OPAQUE, false, false, null),
                REPLACEMENT, new X6MaterialIntent(id("textures/replacement_plan.png"), X6MaterialMode.OPAQUE, false, false, null)))
                .plan().orElseThrow();

        X6VariantDefinition mesh = variant("mesh", X6VariantKind.MESH);
        X6VariantDefinition material = variant("material", X6VariantKind.MATERIAL);
        X6VariantDefinition skin = variant("skin", X6VariantKind.SKIN);
        X6VariantDefinition damage = variant("damage", X6VariantKind.DAMAGE_STAGE);
        X6VariantSelectionPlan selection = new X6VariantSelectionPlan(
                KEY,
                GENERATION,
                Map.of(mesh.variantId(), mesh, material.variantId(), material, skin.variantId(), skin, damage.variantId(), damage),
                List.of(selected(mesh), selected(material), selected(skin), selected(damage)));
        RenderMaterial materialVariant = material("variant_material", RenderLayer.CUTOUT, false, false);
        X6VariantApplicationPlan variants = X6VariantPlanCompiler.prepare(
                selection,
                geometry,
                materials,
                new X6VariantEffectCatalog(KEY, GENERATION, Map.of(
                        mesh.variantId(), new X6VariantEffect.Mesh(REPLACEMENT),
                        material.variantId(), new X6VariantEffect.Material(materialVariant),
                        skin.variantId(), new X6VariantEffect.Skin(id("textures/variant_skin.png")),
                        damage.variantId(), new X6VariantEffect.DamageStage(0x80FFFFFF))))
                .plan().orElseThrow();
        RenderMaterial explicitSecondary = material("explicit_secondary", RenderLayer.SOLID, false, false);
        X6RenderLayerPlan layers = X6RenderLayerPlanner.prepare(KEY, GENERATION, List.of(
                layer(X6LayerType.GLOW, "glow", Optional.empty()),
                layer(X6LayerType.OVERLAY, "overlay", Optional.empty()),
                layer(X6LayerType.DAMAGE_FLASH, "damage", Optional.empty()),
                layer(X6LayerType.SECONDARY_TEXTURE, "secondary", Optional.of(explicitSecondary)),
                layer(X6LayerType.OUTLINE, "outline", Optional.empty()),
                layer(X6LayerType.SHADOW, "shadow", Optional.empty())), geometry).plan().orElseThrow();
        X6MaterialProviderGeneration providers = X6MaterialProviderGeneration.prepareAndPublish(KEY, GENERATION, List.of(), List.of());
        X6TestLifecycleDrainDispatcher lifecycleOwner = new X6TestLifecycleDrainDispatcher();
        X6PreparedRenderPlan plan = X6PreparedRenderPlanFactory.prepare(
                variants, layers, materials, geometry, providers, binding, lifecycleOwner).plan().orElseThrow();
        try {
            Map<X6LayerType, X6PreparedLayerSubmission> byType = plan.layerSubmissions().stream()
                    .collect(java.util.stream.Collectors.toMap(value -> value.layer().type(), value -> value));
            RenderMaterial finalMaterial = variants.draws().getFirst().material();
            assertEquals(id("textures/variant_skin.png"), finalMaterial.textureId());
            assertSame(geometry.binding(REPLACEMENT), variants.draws().getFirst().binding());
            assertEquals(finalMaterial.textureId(), byType.get(X6LayerType.GLOW).material().textureId());
            assertTrue(byType.get(X6LayerType.GLOW).material().emissive());
            assertEquals(X6RenderPhase.POST_BASE, byType.get(X6LayerType.GLOW).semantics().phase());
            assertEquals(X6LightSemantic.FULL_BRIGHT, byType.get(X6LayerType.GLOW).semantics().light());
            assertEquals(Minecraft2612StaticRigidRenderBackend.RenderTypePath.ENTITY_CUTOUT_CULL,
                    Minecraft2612StaticRigidRenderBackend.renderTypePathFor(byType.get(X6LayerType.GLOW).material()));
            assertEquals(finalMaterial.textureId(), byType.get(X6LayerType.OVERLAY).material().textureId());
            assertEquals(X6RenderPhase.OVERLAY, byType.get(X6LayerType.OVERLAY).semantics().phase());
            assertEquals(X6CullingSemantic.CULL, byType.get(X6LayerType.OVERLAY).semantics().culling());
            assertEquals(finalMaterial.textureId(), byType.get(X6LayerType.DAMAGE_FLASH).material().textureId());
            assertEquals(X6RenderPhase.OVERLAY, byType.get(X6LayerType.DAMAGE_FLASH).semantics().phase());
            assertEquals(explicitSecondary, byType.get(X6LayerType.SECONDARY_TEXTURE).material());
            assertEquals(X6TextureTarget.SECONDARY_TEXTURE, byType.get(X6LayerType.SECONDARY_TEXTURE).semantics().textureTarget());
            assertEquals(Minecraft2612StaticRigidRenderBackend.RenderTypePath.ENTITY_SOLID,
                    Minecraft2612StaticRigidRenderBackend.renderTypePathFor(byType.get(X6LayerType.SECONDARY_TEXTURE).material()));
            assertEquals(finalMaterial.textureId(), byType.get(X6LayerType.OUTLINE).material().textureId());
            assertEquals(RenderLayer.CUTOUT, byType.get(X6LayerType.OUTLINE).material().layer());
            assertEquals(X6CullingSemantic.NO_CULL, byType.get(X6LayerType.OUTLINE).semantics().culling());
            assertEquals(X6RenderPhase.PRESENTATION, byType.get(X6LayerType.OUTLINE).semantics().phase());
            assertEquals(Minecraft2612StaticRigidRenderBackend.RenderTypePath.ENTITY_CUTOUT,
                    Minecraft2612StaticRigidRenderBackend.renderTypePathFor(byType.get(X6LayerType.OUTLINE).material()));
            assertEquals(finalMaterial.textureId(), byType.get(X6LayerType.SHADOW).material().textureId());
            assertEquals(RenderLayer.TRANSLUCENT, byType.get(X6LayerType.SHADOW).material().layer());
            assertEquals(X6BlendSemantic.TRANSLUCENT, byType.get(X6LayerType.SHADOW).semantics().blend());
            assertEquals(X6RenderPhase.PRESENTATION, byType.get(X6LayerType.SHADOW).semantics().phase());
            assertEquals(Minecraft2612StaticRigidRenderBackend.RenderTypePath.ENTITY_TRANSLUCENT,
                    Minecraft2612StaticRigidRenderBackend.renderTypePathFor(byType.get(X6LayerType.SHADOW).material()));
            assertEquals(0x80FFFFFF, byType.get(X6LayerType.DAMAGE_FLASH).target().draw().argbTint());
            assertSame(variants.draws().getFirst().binding(), byType.get(X6LayerType.GLOW).target().draw().binding());
        } finally {
            plan.close();
            lifecycleOwner.runAll();
            providers.close();
        }
    }

    @Test
    void exactHandleAndStaticNodeRangeAreValidatedBeforeAPlanCanPublish() {
        PreparedRenderPrimitive primitive = primitive(0, material("base", RenderLayer.SOLID, false, false));
        X6PreparedGeometryCatalog geometry = new X6PreparedGeometryCatalog(KEY, GENERATION, Map.of(PART, primitive));
        Inputs inputs = inputs(geometry);
        X6TestRenderHandle exact = handle(List.of(primitive), Map.of(0, Transform.IDENTITY), false);
        X6MaterialProviderGeneration providers = X6MaterialProviderGeneration.prepareAndPublish(KEY, GENERATION, List.of(), List.of());
        try {
            X6TestLifecycleDrainDispatcher lifecycleOwner = new X6TestLifecycleDrainDispatcher();
            X6PreparedRenderPlan plan = X6PreparedRenderPlanFactory.prepare(
                    inputs.variants(), inputs.layers(), inputs.materials(), geometry, providers, snapshot(exact), lifecycleOwner)
                    .plan().orElseThrow();
            try {
                PreparedRenderPrimitive sameKeyDifferentPrimitive = primitive(0, material("different", RenderLayer.SOLID, false, false));
                assertGeometryFailure(X6PreparedRenderPlanFactory.prepare(
                        inputs.variants(), inputs.layers(), inputs.materials(), geometry, providers,
                        snapshot(handle(List.of(sameKeyDifferentPrimitive), Map.of(0, Transform.IDENTITY), false))));
                assertGeometryFailure(X6PreparedRenderPlanFactory.prepare(
                        inputs.variants(), inputs.layers(), inputs.materials(), geometry, providers,
                        snapshot(handle(List.of(primitive), Map.of(0, Transform.IDENTITY), true))));
                assertGenerationFailure(X6PreparedRenderPlanFactory.prepare(
                        inputs.variants(), inputs.layers(), inputs.materials(), geometry, providers,
                        snapshot(handle(List.of(primitive), Map.of(0, Transform.IDENTITY), false, GENERATION + 1L))));
                assertThrows(IllegalArgumentException.class, () -> X6PlanSubmitter.submit(
                        plan,
                        snapshot(handle(List.of(primitive), Map.of(0, Transform.IDENTITY), false)),
                        new RenderSubmissionContext(new com.mojang.blaze3d.vertex.PoseStack(), new net.minecraft.client.renderer.SubmitNodeStorage()),
                        (child, context) -> { }));
            } finally {
                plan.close();
                lifecycleOwner.runAll();
            }
        } finally {
            providers.close();
        }

        assertThrows(IllegalArgumentException.class, () -> new PreparedRenderPrimitive(
                -1, primitive.geometry(), primitive.material()));
        PreparedRenderPrimitive outOfRange = primitive(1, material("out_of_range", RenderLayer.SOLID, false, false));
        X6PreparedGeometryCatalog outOfRangeGeometry = new X6PreparedGeometryCatalog(KEY, GENERATION, Map.of(PART, outOfRange));
        Inputs outOfRangeInputs = inputs(outOfRangeGeometry);
        X6MaterialProviderGeneration outOfRangeProviders = X6MaterialProviderGeneration.prepareAndPublish(KEY, GENERATION, List.of(), List.of());
        try {
            assertGeometryFailure(X6PreparedRenderPlanFactory.prepare(
                    outOfRangeInputs.variants(), outOfRangeInputs.layers(), outOfRangeInputs.materials(), outOfRangeGeometry,
                    outOfRangeProviders, snapshot(handle(List.of(outOfRange), Map.of(0, Transform.IDENTITY), false))));
        } finally {
            outOfRangeProviders.close();
        }
    }

    @Test
    void optionalSelectedFallbackAndAbsentBindingsPreserveX1OutcomeSemantics() {
        MaterialProvider provider = provider("selected", false);
        CapabilityRequest optional = CapabilityRequest.optional(
                CAPABILITY, VERSION_RANGE, new CapabilityFallback(id("fallback"), "declared equivalent standard route"));
        X6MaterialProviderGeneration selected = X6MaterialProviderGeneration.prepareAndPublish(
                KEY, GENERATION, List.of(provider), List.of(optional));
        try {
            assertTrue(selected.publishable());
            assertEquals(List.of(new X6MaterialProviderBinding(
                    CAPABILITY, CapabilitySelectionOutcome.SELECTED, Optional.of(id("selected")), Optional.empty())),
                    selected.materialBindings());
        } finally {
            selected.close();
        }

        X6MaterialProviderGeneration fallback = X6MaterialProviderGeneration.prepareAndPublish(KEY, GENERATION + 1L, List.of(), List.of(optional));
        try {
            assertTrue(fallback.publishable());
            assertEquals(List.of(new X6MaterialProviderBinding(
                    CAPABILITY, CapabilitySelectionOutcome.FALLBACK, Optional.empty(), Optional.of(id("fallback")))),
                    fallback.materialBindings());
        } finally {
            fallback.close();
        }

        X6MaterialProviderGeneration absent = X6MaterialProviderGeneration.prepareAndPublish(KEY, GENERATION + 2L, List.of(), List.of());
        try {
            assertTrue(absent.publishable());
            assertTrue(absent.materialBindings().isEmpty());
        } finally {
            absent.close();
        }
    }

    @Test
    void ordinaryRuntimeMetadataAndHostileSupportedSetAreContainedBeforeLifecycleOwnershipLeaks() {
        OrdinaryMetadataProvider ordinary = new OrdinaryMetadataProvider();
        X6MaterialProviderGeneration failed = X6MaterialProviderGeneration.prepareAndPublish(
                KEY, GENERATION, List.of(ordinary), List.of(CapabilityRequest.required(CAPABILITY, VERSION_RANGE)));
        assertFalse(failed.publishable());
        assertTrue(failed.diagnostics().stream().anyMatch(value -> value.code() == X6DiagnosticCode.PROVIDER_FAILURE));
        assertEquals(0, ordinary.closeCalls.get(), "a rejected metadata provider remains caller-owned before X1 session creation");

        X6MaterialProviderGeneration sizeContainsSafe = X6MaterialProviderGeneration.prepareAndPublish(
                KEY, GENERATION + 1L, List.of(provider("size_contains", true)), List.of(CapabilityRequest.required(CAPABILITY, VERSION_RANGE)));
        try {
            assertTrue(sizeContainsSafe.publishable(), "size/contains must not be called while snapshotting supported capabilities");
            assertEquals(CapabilitySelectionOutcome.SELECTED, sizeContainsSafe.materialBindings().getFirst().outcome());
        } finally {
            sizeContainsSafe.close();
        }

        MaterialProvider hostile = new HostileSupportedSetProvider();
        X6MaterialProviderGeneration fallback = X6MaterialProviderGeneration.prepareAndPublish(
                KEY,
                GENERATION + 2L,
                List.of(hostile),
                List.of(CapabilityRequest.optional(
                        CAPABILITY, VERSION_RANGE, new CapabilityFallback(id("fallback"), "declared equivalent standard route"))));
        try {
            assertTrue(fallback.publishable(), "hostile size/contains must not escape the metadata snapshot boundary");
            assertEquals(CapabilitySelectionOutcome.FALLBACK, fallback.materialBindings().getFirst().outcome());
        } finally {
            fallback.close();
        }
    }

    @Test
    void ordinaryRuntimeFatalAndHostileOfferMetadataPreserveContainmentAndPrimaryFatalIdentity() {
        RuntimeMetadataProvider runtime = new RuntimeMetadataProvider();
        X6MaterialProviderGeneration runtimeResult = X6MaterialProviderGeneration.prepareAndPublish(
                KEY, GENERATION, List.of(runtime), List.of(CapabilityRequest.required(CAPABILITY, VERSION_RANGE)));
        assertFalse(runtimeResult.publishable());
        assertTrue(runtimeResult.diagnostics().stream().anyMatch(value -> value.code() == X6DiagnosticCode.PROVIDER_FAILURE));
        assertEquals(0, runtime.closeCalls.get(), "ordinary rejected metadata remains caller-owned");

        FatalMetadataProvider fatal = new FatalMetadataProvider();
        FatalMetadataError fatalThrown = assertThrows(FatalMetadataError.class, () -> X6MaterialProviderGeneration.prepareAndPublish(
                KEY, GENERATION + 1L, List.of(fatal), List.of(CapabilityRequest.required(CAPABILITY, VERSION_RANGE))));
        assertSame(fatal.failure, fatalThrown, "cleanup failure must not replace the primary VM fatal identity");
        assertEquals(0, fatal.closeCalls.get(), "fatal metadata must not trigger a direct pre-session close");

        ThreadDeathMetadataProvider threadDeath = new ThreadDeathMetadataProvider();
        ThreadDeath deathThrown = assertThrows(ThreadDeath.class, () -> X6MaterialProviderGeneration.prepareAndPublish(
                KEY, GENERATION + 2L, List.of(threadDeath), List.of(CapabilityRequest.required(CAPABILITY, VERSION_RANGE))));
        assertSame(threadDeath.failure, deathThrown, "cleanup failure must not replace ThreadDeath identity");
        assertEquals(0, threadDeath.closeCalls.get(), "ThreadDeath metadata must not trigger a direct pre-session close");

        HostileOfferProvider hostileOffers = new HostileOfferProvider();
        X6MaterialProviderGeneration offerResult = X6MaterialProviderGeneration.prepareAndPublish(
                KEY, GENERATION + 3L, List.of(hostileOffers), List.of(CapabilityRequest.required(CAPABILITY, VERSION_RANGE)));
        assertFalse(offerResult.publishable(), "registry offer traversal failure must remain a local X6 result");
        assertEquals(0, hostileOffers.closeCalls.get(), "a pre-session registry failure remains caller-owned");
    }

    private static Inputs inputs(X6PreparedGeometryCatalog geometry) {
        X6MaterialPlan materials = X6MaterialPlan.prepare(KEY, GENERATION, geometry.bindings().keySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        value -> value,
                        value -> new X6MaterialIntent(id("textures/" + value.path() + ".png"), X6MaterialMode.OPAQUE, false, false, null),
                        (left, right) -> left,
                        LinkedHashMap::new))).plan().orElseThrow();
        X6VariantApplicationPlan variants = new X6VariantApplicationPlan(KEY, GENERATION, geometry.bindings().entrySet().stream()
                .map(entry -> new X6DrawPrimitive(entry.getKey(), entry.getValue(), materials.material(entry.getKey()), 0xFFFFFFFF))
                .toList(), List.of());
        X6RenderLayerPlan layers = X6RenderLayerPlanner.prepare(KEY, GENERATION, List.of(), geometry).plan().orElseThrow();
        return new Inputs(variants, layers, materials);
    }

    private static X6LayerEntry layer(X6LayerType type, String name, Optional<RenderMaterial> material) {
        return new X6LayerEntry(id(name), type, switch (type) {
            case GLOW, SECONDARY_TEXTURE, PER_BONE_PART_TEXTURE -> X6RenderPhase.POST_BASE;
            case OVERLAY, DAMAGE_FLASH -> X6RenderPhase.OVERLAY;
            case ATTACHMENT -> X6RenderPhase.ATTACHMENT;
            case OUTLINE, SHADOW -> X6RenderPhase.PRESENTATION;
        }, type.ordinal(), X6LayerTargetSelector.part(PART), material, Optional.empty());
    }

    private static X6VariantDefinition variant(String name, X6VariantKind kind) {
        return new X6VariantDefinition(id(name), kind, PART);
    }

    private static X6VariantSelection selected(X6VariantDefinition definition) {
        return new X6VariantSelection(id("selector_" + definition.variantId().path()), X6VariantRuleOutcome.MATCHED, Optional.of(definition.variantId()));
    }

    private static void assertGeometryFailure(X6PlanResult<X6PreparedRenderPlan> result) {
        assertFalse(result.publishable());
        assertTrue(result.diagnostics().stream().anyMatch(value -> value.code() == X6DiagnosticCode.GEOMETRY_MISMATCH));
    }

    private static void assertGenerationFailure(X6PlanResult<X6PreparedRenderPlan> result) {
        assertFalse(result.publishable());
        assertTrue(result.diagnostics().stream().anyMatch(value -> value.code() == X6DiagnosticCode.GENERATION_MISMATCH));
    }

    private static X6TestRenderHandle handle(
            List<PreparedRenderPrimitive> primitives, Map<Integer, Transform> nodes, boolean missing) {
        return handle(primitives, nodes, missing, GENERATION);
    }

    private static X6TestRenderHandle handle(
            List<PreparedRenderPrimitive> primitives, Map<Integer, Transform> nodes, boolean missing, long generation) {
        return new X6TestRenderHandle(KEY, generation, primitives, nodes, missing);
    }

    private static ModelRenderSnapshot snapshot(X6TestRenderHandle handle) {
        return new ModelRenderSnapshot(
                handle,
                Transform.IDENTITY,
                Minecraft2612StaticRigidRenderBackend.FULL_BRIGHT_PACKED_LIGHT,
                0,
                0xFFFFFFFF,
                RenderVisibility.VISIBLE,
                new CullingMetadata(handle.bounds(), true));
    }

    private static PreparedRenderPrimitive primitive(int node, RenderMaterial material) {
        return new PreparedRenderPrimitive(node, StaticGeometry.of(
                new float[] {0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f},
                new float[] {0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f},
                new float[] {0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f},
                new int[] {0, 1, 2}), material);
    }

    private static RenderMaterial material(String name, RenderLayer layer, boolean emissive, boolean doubleSided) {
        return new RenderMaterial(id("textures/" + name + ".png"), layer, emissive, doubleSided, 0xFFFFFFFF, false);
    }

    private static MaterialProvider provider(String name, boolean hostile) {
        BlendResourceId providerId = id(name);
        return new MaterialProvider() {
            @Override
            public BlendResourceId providerId() {
                return providerId;
            }

            @Override
            public Collection<CapabilityOffer> offers() {
                return List.of(new CapabilityOffer(providerId, CAPABILITY, CapabilityVersion.CURRENT_PROTOCOL, 1));
            }

            @Override
            public Set<BlendResourceId> supportedMaterialCapabilities() {
                return hostile ? new java.util.AbstractSet<>() {
                    @Override
                    public java.util.Iterator<BlendResourceId> iterator() {
                        return Set.of(CAPABILITY).iterator();
                    }

                    @Override
                    public int size() {
                        throw new AssertionError("size must not be called");
                    }
                } : Set.of(CAPABILITY);
            }
        };
    }

    private static BlendResourceId id(String path) {
        return BlendResourceId.parse("x6_r2:" + path);
    }

    private record Inputs(X6VariantApplicationPlan variants, X6RenderLayerPlan layers, X6MaterialPlan materials) {
    }

    private static final class OrdinaryMetadataProvider implements MaterialProvider {
        private final AtomicInteger closeCalls = new AtomicInteger();

        @Override
        public BlendResourceId providerId() {
            throw new IllegalStateException("ordinary metadata failure");
        }

        @Override
        public Collection<CapabilityOffer> offers() {
            throw new AssertionError("offers must not be reached after providerId failure");
        }

        @Override
        public Set<BlendResourceId> supportedMaterialCapabilities() {
            throw new AssertionError("supported must not be reached after providerId failure");
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }
    }

    private static final class HostileSupportedSetProvider implements MaterialProvider {
        private final BlendResourceId providerId = id("hostile");

        @Override
        public BlendResourceId providerId() {
            return providerId;
        }

        @Override
        public Collection<CapabilityOffer> offers() {
            return List.of(new CapabilityOffer(providerId, CAPABILITY, CapabilityVersion.CURRENT_PROTOCOL, 1));
        }

        @Override
        public Set<BlendResourceId> supportedMaterialCapabilities() {
            return new java.util.AbstractSet<>() {
                @Override
                public java.util.Iterator<BlendResourceId> iterator() {
                    throw new IllegalStateException("hostile iterator");
                }

                @Override
                public int size() {
                    throw new AssertionError("hostile size");
                }

                @Override
                public boolean contains(Object value) {
                    throw new AssertionError("hostile contains");
                }
            };
        }
    }

    private static final class RuntimeMetadataProvider implements MaterialProvider {
        private final AtomicInteger closeCalls = new AtomicInteger();

        @Override
        public BlendResourceId providerId() {
            throw new IllegalStateException("ordinary metadata failure");
        }

        @Override
        public Collection<CapabilityOffer> offers() {
            return List.of();
        }

        @Override
        public Set<BlendResourceId> supportedMaterialCapabilities() {
            return Set.of();
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }
    }

    private static final class FatalMetadataProvider implements MaterialProvider {
        private final FatalMetadataError failure = new FatalMetadataError();
        private final AtomicInteger closeCalls = new AtomicInteger();

        @Override
        public BlendResourceId providerId() {
            throw failure;
        }

        @Override
        public Collection<CapabilityOffer> offers() {
            return List.of();
        }

        @Override
        public Set<BlendResourceId> supportedMaterialCapabilities() {
            return Set.of();
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            throw new AssertionError("secondary close failure");
        }
    }

    private static final class ThreadDeathMetadataProvider implements MaterialProvider {
        private final ThreadDeath failure = new ThreadDeath();
        private final AtomicInteger closeCalls = new AtomicInteger();

        @Override
        public BlendResourceId providerId() {
            throw failure;
        }

        @Override
        public Collection<CapabilityOffer> offers() {
            return List.of();
        }

        @Override
        public Set<BlendResourceId> supportedMaterialCapabilities() {
            return Set.of();
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            throw new AssertionError("secondary close failure");
        }
    }

    private static final class HostileOfferProvider implements MaterialProvider {
        private final BlendResourceId providerId = id("hostile_offers");
        private final AtomicInteger closeCalls = new AtomicInteger();

        @Override
        public BlendResourceId providerId() {
            return providerId;
        }

        @Override
        public Collection<CapabilityOffer> offers() {
            return new java.util.AbstractCollection<>() {
                @Override
                public java.util.Iterator<CapabilityOffer> iterator() {
                    throw new IllegalStateException("hostile offers iterator");
                }

                @Override
                public int size() {
                    throw new AssertionError("hostile offers size");
                }
            };
        }

        @Override
        public Set<BlendResourceId> supportedMaterialCapabilities() {
            return Set.of(CAPABILITY);
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }
    }

    private static final class FatalMetadataError extends VirtualMachineError {
        private static final long serialVersionUID = 1L;
    }
}
