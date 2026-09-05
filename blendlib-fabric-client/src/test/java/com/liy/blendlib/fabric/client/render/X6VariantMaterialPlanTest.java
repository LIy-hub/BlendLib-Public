package com.liy.blendlib.fabric.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.model.Transform;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** X6 prepare-time coverage for variants, catalog ownership, material binding, and manifest bytes. */
class X6VariantMaterialPlanTest {
    private static final BlendModelKey KEY = BlendModelKey.parse("x6_test:actor/base");
    private static final long GENERATION = 42L;
    private static final BlendResourceId STATE = id("state");
    private static final BlendResourceId SKIN_PART = id("part_skin");
    private static final BlendResourceId MATERIAL_PART = id("part_material");
    private static final BlendResourceId MESH_PART = id("part_mesh");
    private static final BlendResourceId MESH_REPLACEMENT = id("part_mesh_replacement");
    private static final BlendResourceId DAMAGE_PART = id("part_damage");
    private static final BlendResourceId VISIBILITY_PART = id("part_visibility");
    private static final BlendResourceId SOCKET = id("socket");

    @Test
    void dataAndCodeInputsUseOneSelectorAndCompileAllSixKindsAgainstExplicitMaterials() throws IOException {
        X6VariantManifest dataManifest = X6VariantManifestParser.parseForPrepare(resourceBytes("x6/variant-manifest-v1.txt"));
        X6VariantManifest codeManifest = codeManifest();
        assertEquals(codeManifest, dataManifest, "the fixture must exercise the code/data parity path");

        X6VariantContext active = new X6VariantContext(Map.of(STATE, "active"));
        X6PlanResult<X6VariantSelectionPlan> dataSelection = X6VariantSelectionEngine.prepare(KEY, GENERATION, dataManifest, active);
        X6PlanResult<X6VariantSelectionPlan> codeSelection = X6VariantSelectionEngine.prepare(KEY, GENERATION, codeManifest, active);
        assertTrue(dataSelection.publishable());
        assertEquals(codeSelection.plan(), dataSelection.plan());
        assertEquals(6, dataSelection.plan().orElseThrow().selections().size());

        X6PreparedGeometryCatalog geometry = geometry();
        X6MaterialPlan materials = materialPlan(geometry);
        ModelRenderSnapshot equipment = snapshot(BlendModelKey.parse("x6_test:equipment/child"), GENERATION);
        X6VariantEffectCatalog effects = new X6VariantEffectCatalog(KEY, GENERATION, Map.of(
                id("skin"), new X6VariantEffect.Skin(id("textures/skin.png")),
                id("material"), new X6VariantEffect.Material(material("material_replacement", RenderLayer.CUTOUT, false, false)),
                id("mesh"), new X6VariantEffect.Mesh(MESH_REPLACEMENT),
                id("equipment"), new X6VariantEffect.Equipment(equipment),
                id("damage"), new X6VariantEffect.DamageStage(0x80FF0000),
                id("visibility"), new X6VariantEffect.PartVisibility(false)));

        X6PlanResult<X6VariantApplicationPlan> compiled = X6VariantPlanCompiler.prepare(
                dataSelection.plan().orElseThrow(), geometry, materials, effects);
        assertTrue(compiled.publishable());
        X6VariantApplicationPlan plan = compiled.plan().orElseThrow();
        Map<BlendResourceId, X6DrawPrimitive> draws = plan.draws().stream()
                .collect(java.util.stream.Collectors.toMap(X6DrawPrimitive::partId, value -> value));

        assertEquals(5, draws.size());
        assertEquals(id("textures/skin.png"), draws.get(SKIN_PART).material().textureId());
        assertEquals(RenderLayer.CUTOUT, draws.get(MATERIAL_PART).material().layer());
        assertSame(geometry.binding(MESH_REPLACEMENT), draws.get(MESH_PART).binding());
        assertEquals(materials.material(MESH_PART), draws.get(MESH_PART).material(), "mesh does not silently restore primitive material");
        assertEquals(0x80FF0000, draws.get(DAMAGE_PART).argbTint());
        assertFalse(draws.containsKey(VISIBILITY_PART));
        assertEquals(List.of(equipment), plan.equipmentSnapshots());
    }

    @Test
    void samePartCompositionIsPhaseOrderedAndAmbiguousOverlapFailsClosed() {
        X6PreparedGeometryCatalog geometry = geometry();
        X6MaterialPlan materials = materialPlan(geometry);
        X6VariantDefinition mesh = variant("compose_mesh", X6VariantKind.MESH, MESH_PART);
        X6VariantDefinition material = variant("compose_material", X6VariantKind.MATERIAL, MESH_PART);
        X6VariantDefinition skin = variant("compose_skin", X6VariantKind.SKIN, MESH_PART);
        X6VariantDefinition damage = variant("compose_damage", X6VariantKind.DAMAGE_STAGE, MESH_PART);
        X6VariantDefinition visible = variant("compose_visible", X6VariantKind.PART_VISIBILITY, MESH_PART);
        Map<BlendResourceId, X6VariantDefinition> definitions = Map.of(
                mesh.variantId(), mesh, material.variantId(), material, skin.variantId(), skin, damage.variantId(), damage, visible.variantId(), visible);
        List<X6VariantSelection> selections = List.of(
                selected("z", mesh), selected("y", material), selected("a", skin), selected("m", damage), selected("b", visible));
        X6VariantEffectCatalog effects = new X6VariantEffectCatalog(KEY, GENERATION, Map.of(
                mesh.variantId(), new X6VariantEffect.Mesh(MESH_REPLACEMENT),
                material.variantId(), new X6VariantEffect.Material(material("composed", RenderLayer.CUTOUT, false, false)),
                skin.variantId(), new X6VariantEffect.Skin(id("textures/composed_skin.png")),
                damage.variantId(), new X6VariantEffect.DamageStage(0x80FF0000),
                visible.variantId(), new X6VariantEffect.PartVisibility(true)));
        X6VariantSelectionPlan forward = new X6VariantSelectionPlan(KEY, GENERATION, definitions, selections);
        X6VariantSelectionPlan reversed = new X6VariantSelectionPlan(KEY, GENERATION, definitions, selections.reversed());
        X6VariantApplicationPlan first = X6VariantPlanCompiler.prepare(forward, geometry, materials, effects).plan().orElseThrow();
        X6VariantApplicationPlan second = X6VariantPlanCompiler.prepare(reversed, geometry, materials, effects).plan().orElseThrow();
        X6DrawPrimitive composed = first.draws().stream().filter(value -> value.partId().equals(MESH_PART)).findFirst().orElseThrow();
        assertEquals(first.draws(), second.draws());
        assertSame(geometry.binding(MESH_REPLACEMENT), composed.binding());
        assertEquals(id("textures/composed_skin.png"), composed.material().textureId());
        assertEquals(RenderLayer.CUTOUT, composed.material().layer());
        assertEquals(0x80FF0000, composed.argbTint());

        X6VariantEffectCatalog hiddenEffects = new X6VariantEffectCatalog(KEY, GENERATION, Map.of(
                mesh.variantId(), new X6VariantEffect.Mesh(MESH_REPLACEMENT),
                material.variantId(), new X6VariantEffect.Material(material("composed", RenderLayer.CUTOUT, false, false)),
                skin.variantId(), new X6VariantEffect.Skin(id("textures/composed_skin.png")),
                damage.variantId(), new X6VariantEffect.DamageStage(0x80FF0000),
                visible.variantId(), new X6VariantEffect.PartVisibility(false)));
        assertFalse(X6VariantPlanCompiler.prepare(forward, geometry, materials, hiddenEffects).plan().orElseThrow().draws().stream()
                .anyMatch(value -> value.partId().equals(MESH_PART)), "visibility is the explicit final composition phase");

        X6VariantDefinition otherSkin = variant("compose_other_skin", X6VariantKind.SKIN, MESH_PART);
        X6VariantSelectionPlan duplicate = new X6VariantSelectionPlan(
                KEY,
                GENERATION,
                Map.of(skin.variantId(), skin, otherSkin.variantId(), otherSkin),
                List.of(selected("one", skin), selected("two", otherSkin)));
        X6PlanResult<X6VariantApplicationPlan> duplicateResult = X6VariantPlanCompiler.prepare(
                duplicate,
                geometry,
                materials,
                new X6VariantEffectCatalog(KEY, GENERATION, Map.of(
                        skin.variantId(), new X6VariantEffect.Skin(id("textures/a.png")),
                        otherSkin.variantId(), new X6VariantEffect.Skin(id("textures/b.png")))));
        assertDiagnostic(duplicateResult, X6DiagnosticCode.VARIANT_CONFLICT);

        X6VariantDefinition equipment = variant("compose_equipment", X6VariantKind.EQUIPMENT, MESH_PART);
        X6VariantSelectionPlan equipmentOverlap = new X6VariantSelectionPlan(
                KEY,
                GENERATION,
                Map.of(mesh.variantId(), mesh, equipment.variantId(), equipment),
                List.of(selected("mesh", mesh), selected("equipment", equipment)));
        assertDiagnostic(X6VariantPlanCompiler.prepare(
                equipmentOverlap,
                geometry,
                materials,
                new X6VariantEffectCatalog(KEY, GENERATION, Map.of(
                        mesh.variantId(), new X6VariantEffect.Mesh(MESH_REPLACEMENT),
                        equipment.variantId(), new X6VariantEffect.Equipment(snapshot(BlendModelKey.parse("x6_test:equipment/overlap"), GENERATION))))),
                X6DiagnosticCode.VARIANT_CONFLICT);
    }

    @Test
    void controlledMeshAndMaterialCoverageRejectForeignNodesMissingPartsAndGenerationMixes() {
        X6PreparedGeometryCatalog geometry = geometry();
        X6MaterialPlan materials = materialPlan(geometry);
        X6VariantDefinition mesh = variant("mesh_only", X6VariantKind.MESH, MESH_PART);
        X6VariantSelectionPlan selectedMesh = new X6VariantSelectionPlan(
                KEY, GENERATION, Map.of(mesh.variantId(), mesh), List.of(selected("mesh", mesh)));
        X6VariantEffectCatalog effect = new X6VariantEffectCatalog(
                KEY, GENERATION, Map.of(mesh.variantId(), new X6VariantEffect.Mesh(MESH_REPLACEMENT)));
        assertTrue(X6VariantPlanCompiler.prepare(selectedMesh, geometry, materials, effect).publishable());

        X6PreparedGeometryCatalog wrongNode = new X6PreparedGeometryCatalog(KEY, GENERATION, Map.of(
                MESH_PART, primitive(2, material("mesh", RenderLayer.SOLID, false, false)),
                MESH_REPLACEMENT, primitive(99, material("replacement", RenderLayer.SOLID, false, false))));
        X6MaterialPlan wrongNodeMaterials = materialPlan(wrongNode);
        assertDiagnostic(
                X6VariantPlanCompiler.prepare(selectedMesh, wrongNode, wrongNodeMaterials, effect),
                X6DiagnosticCode.GEOMETRY_MISMATCH);

        X6PlanResult<X6MaterialPlan> incomplete = X6MaterialPlan.prepare(KEY, GENERATION, Map.of(
                SKIN_PART, intent("only_one")));
        assertTrue(incomplete.publishable());
        X6PlanResult<X6VariantApplicationPlan> incompleteCompile = X6VariantPlanCompiler.prepare(
                selectedMesh, geometry, incomplete.plan().orElseThrow(), effect);
        assertDiagnostic(incompleteCompile, X6DiagnosticCode.MATERIAL_UNSUPPORTED);

        X6MaterialPlan otherGeneration = X6MaterialPlan.prepare(KEY, GENERATION + 1L, materialIntents(geometry)).plan().orElseThrow();
        assertDiagnostic(X6VariantPlanCompiler.prepare(selectedMesh, geometry, otherGeneration, effect), X6DiagnosticCode.GENERATION_MISMATCH);
    }

    @Test
    void selectionDoesNotDependOnInputRegistrationOrderAndFailsClosedOnTopPriorityTies() {
        X6VariantManifest original = codeManifest();
        X6VariantManifest reversed = new X6VariantManifest(
                original.formatVersion(), original.variants().reversed(), original.rules().reversed(), original.defaults());
        X6VariantContext active = new X6VariantContext(Map.of(STATE, "active"));
        assertEquals(
                X6VariantSelectionEngine.prepare(KEY, GENERATION, original, active).plan(),
                X6VariantSelectionEngine.prepare(KEY, GENERATION, reversed, active).plan());

        BlendResourceId selector = id("tie_selector");
        X6VariantManifest tied = new X6VariantManifest(
                X6VariantManifest.FORMAT_VERSION,
                List.of(new X6VariantDefinition(id("first"), X6VariantKind.SKIN, SKIN_PART),
                        new X6VariantDefinition(id("second"), X6VariantKind.SKIN, SKIN_PART)),
                List.of(
                        new X6VariantRule(id("tie_a"), selector, 9, Map.of(STATE, "active"), id("first")),
                        new X6VariantRule(id("tie_b"), selector, 9, Map.of(STATE, "active"), id("second"))),
                Map.of());
        assertDiagnostic(X6VariantSelectionEngine.prepare(KEY, GENERATION, tied, active), X6DiagnosticCode.VARIANT_CONFLICT);
    }

    @Test
    void strictManifestBytesAndOrderedMapsHaveExplicitContracts() {
        String prefix = "format_version=1\n#";
        String exact = prefix + "x".repeat(X6Ids.MAX_MANIFEST_BYTES - prefix.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(1, X6VariantManifestParser.parseForPrepare(exact.getBytes(StandardCharsets.UTF_8)).formatVersion());
        byte[] tooLarge = Arrays.copyOf(exact.getBytes(StandardCharsets.UTF_8), X6Ids.MAX_MANIFEST_BYTES + 1);
        tooLarge[tooLarge.length - 1] = '#';
        assertThrows(IllegalArgumentException.class, () -> X6VariantManifestParser.parseForPrepare(tooLarge));
        assertThrows(IllegalArgumentException.class, () -> X6VariantManifestParser.parseForPrepare(new byte[] {(byte) 0xC3, 0x28}));
        assertThrows(IllegalArgumentException.class, () -> X6VariantManifestParser.parseForPrepare(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '#'}));
        String multibyteComment = "format_version=1\n#" + "中".repeat(1_000);
        assertEquals(1, X6VariantManifestParser.parseForPrepare(multibyteComment.getBytes(StandardCharsets.UTF_8)).formatVersion());

        LinkedHashMap<BlendResourceId, String> reverse = new LinkedHashMap<>();
        reverse.put(id("z"), "value");
        reverse.put(id("a"), "value");
        assertEquals(List.of(id("a"), id("z")), new X6VariantContext(reverse).values().keySet().stream().toList());
        assertEquals(
                geometry().bindings().keySet().stream().sorted(java.util.Comparator.comparing(BlendResourceId::value)).toList(),
                geometry().bindings().keySet().stream().toList());
    }

    @Test
    void baselineFallbackAndHardBoundsAreExplicitRatherThanImplicitlyExpanded() {
        BlendResourceId selector = id("fallback_selector");
        X6VariantManifest fallback = new X6VariantManifest(
                X6VariantManifest.FORMAT_VERSION,
                List.of(new X6VariantDefinition(id("fallback_variant"), X6VariantKind.SKIN, SKIN_PART)),
                List.of(new X6VariantRule(id("never"), selector, 1, Map.of(STATE, "inactive"), id("fallback_variant"))),
                Map.of());
        X6PlanResult<X6VariantSelectionPlan> fallbackResult = X6VariantSelectionEngine.prepare(
                KEY, GENERATION, fallback, new X6VariantContext(Map.of(STATE, "active")));
        assertTrue(fallbackResult.publishable());
        assertEquals(X6VariantRuleOutcome.BASELINE_FALLBACK,
                fallbackResult.plan().orElseThrow().selections().getFirst().outcome());
        assertTrue(fallbackResult.diagnostics().stream().anyMatch(value -> value.code() == X6DiagnosticCode.VARIANT_NO_MATCH));

        List<X6VariantDefinition> tooMany = new ArrayList<>();
        for (int index = 0; index <= X6Ids.MAX_VARIANTS; index++) {
            tooMany.add(new X6VariantDefinition(id("limit_" + index), X6VariantKind.SKIN, SKIN_PART));
        }
        assertDiagnostic(X6VariantSelectionEngine.prepare(
                KEY, GENERATION, new X6VariantManifest(1, tooMany, List.of(), Map.of()), X6VariantContext.empty()), X6DiagnosticCode.VARIANT_LIMIT);
    }

    @Test
    void standardMaterialSubsetIsMappedAtPrepareAndAdvancedModesFailClosed() {
        for (X6MaterialIntent intent : List.of(
                new X6MaterialIntent(id("textures/solid.png"), X6MaterialMode.OPAQUE, false, false, null),
                new X6MaterialIntent(id("textures/cutout.png"), X6MaterialMode.CUTOUT, true, false, null),
                new X6MaterialIntent(id("textures/translucent.png"), X6MaterialMode.TRANSLUCENT, false, true, null))) {
            X6PlanResult<X6MaterialPlan> result = X6MaterialPlan.prepare(KEY, GENERATION, Map.of(SKIN_PART, intent));
            assertTrue(result.publishable(), () -> "expected standard X6 material to publish: " + intent.mode());
            assertNotNull(result.plan().orElseThrow().material(SKIN_PART));
        }
        for (X6MaterialMode mode : List.of(X6MaterialMode.ADDITIVE, X6MaterialMode.COMPLEX_PBR, X6MaterialMode.CUSTOM_SHADER)) {
            X6PlanResult<X6MaterialPlan> result = X6MaterialPlan.prepare(
                    KEY, GENERATION, Map.of(SKIN_PART, new X6MaterialIntent(
                            id("textures/unsupported_" + mode.name().toLowerCase() + ".png"), mode, false, false, null)));
            assertFalse(result.publishable());
            assertTrue(result.diagnostics().stream().anyMatch(value -> value.code() == X6DiagnosticCode.MATERIAL_UNSUPPORTED));
        }
    }

    private static void assertDiagnostic(X6PlanResult<?> result, X6DiagnosticCode code) {
        assertFalse(result.publishable());
        assertTrue(result.diagnostics().stream().anyMatch(value -> value.code() == code), () -> "expected " + code);
    }

    private static X6VariantSelection selected(String selector, X6VariantDefinition definition) {
        return new X6VariantSelection(id("selector_" + selector), X6VariantRuleOutcome.MATCHED, Optional.of(definition.variantId()));
    }

    private static X6VariantManifest codeManifest() {
        List<X6VariantDefinition> variants = List.of(
                variant("skin", X6VariantKind.SKIN, SKIN_PART),
                variant("material", X6VariantKind.MATERIAL, MATERIAL_PART),
                variant("mesh", X6VariantKind.MESH, MESH_PART),
                variant("equipment", X6VariantKind.EQUIPMENT, SOCKET),
                variant("damage", X6VariantKind.DAMAGE_STAGE, DAMAGE_PART),
                variant("visibility", X6VariantKind.PART_VISIBILITY, VISIBILITY_PART));
        List<X6VariantRule> rules = new ArrayList<>();
        Map<BlendResourceId, BlendResourceId> defaults = new LinkedHashMap<>();
        for (X6VariantDefinition variant : variants) {
            BlendResourceId selector = id(variant.variantId().path() + "_selector");
            defaults.put(selector, variant.variantId());
            rules.add(new X6VariantRule(
                    id(variant.variantId().path() + "_rule"), selector, 100, Map.of(STATE, "active"), variant.variantId()));
        }
        return new X6VariantManifest(X6VariantManifest.FORMAT_VERSION, variants, rules, defaults);
    }

    private static X6PreparedGeometryCatalog geometry() {
        return new X6PreparedGeometryCatalog(KEY, GENERATION, Map.of(
                SKIN_PART, primitive(0, material("base_skin", RenderLayer.SOLID, false, false)),
                MATERIAL_PART, primitive(1, material("base_material", RenderLayer.SOLID, false, false)),
                MESH_PART, primitive(2, material("base_mesh", RenderLayer.SOLID, false, false)),
                MESH_REPLACEMENT, primitive(2, material("mesh_replacement", RenderLayer.CUTOUT, false, false)),
                DAMAGE_PART, primitive(3, material("base_damage", RenderLayer.SOLID, false, false)),
                VISIBILITY_PART, primitive(4, material("base_visibility", RenderLayer.SOLID, false, false))));
    }

    private static X6MaterialPlan materialPlan(X6PreparedGeometryCatalog geometry) {
        return X6MaterialPlan.prepare(KEY, geometry.generation(), materialIntents(geometry)).plan().orElseThrow();
    }

    private static Map<BlendResourceId, X6MaterialIntent> materialIntents(X6PreparedGeometryCatalog geometry) {
        Map<BlendResourceId, X6MaterialIntent> result = new LinkedHashMap<>();
        int index = 0;
        for (BlendResourceId partId : geometry.bindings().keySet()) {
            result.put(partId, new X6MaterialIntent(id("textures/material_" + index++ + ".png"), X6MaterialMode.OPAQUE, false, false, null));
        }
        return result;
    }

    private static X6MaterialIntent intent(String name) {
        return new X6MaterialIntent(id("textures/" + name + ".png"), X6MaterialMode.OPAQUE, false, false, null);
    }

    private static PreparedRenderPrimitive primitive(int nodeIndex, RenderMaterial material) {
        return new PreparedRenderPrimitive(nodeIndex, StaticGeometry.of(
                new float[] {0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f},
                new float[] {0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f},
                new float[] {0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f},
                new int[] {0, 1, 2}), material);
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

    private static X6VariantDefinition variant(String name, X6VariantKind kind, BlendResourceId partId) {
        return new X6VariantDefinition(id(name), kind, partId);
    }

    private static RenderMaterial material(String textureName, RenderLayer layer, boolean emissive, boolean doubleSided) {
        return new RenderMaterial(id("textures/" + textureName + ".png"), layer, emissive, doubleSided, 0xFFFFFFFF, false);
    }

    private static BlendResourceId id(String path) {
        return BlendResourceId.parse("x6_test:" + path);
    }

    private static byte[] resourceBytes(String name) throws IOException {
        try (InputStream stream = X6VariantMaterialPlanTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(stream, () -> "missing test resource " + name);
            return stream.readAllBytes();
        }
    }
}
