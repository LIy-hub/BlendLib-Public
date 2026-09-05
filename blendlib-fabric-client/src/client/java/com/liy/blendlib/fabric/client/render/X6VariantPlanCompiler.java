package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Applies a frozen selection to catalog-controlled geometry and a complete material plan. */
public final class X6VariantPlanCompiler {
    private X6VariantPlanCompiler() {
    }

    /**
     * Produces one executable, immutable draw list at generation preparation time.
     *
     * <p>Per-part effects compose in this fixed order: mesh binding, material route, skin texture,
     * damage tint, then final visibility. A part may select at most one effect of each kind. That
     * makes input registration order irrelevant and turns ambiguous same-kind overlap into a hard
     * diagnostic instead of a last-write-wins mutation.</p>
     */
    public static X6PlanResult<X6VariantApplicationPlan> prepare(
            X6VariantSelectionPlan selectionPlan,
            X6PreparedGeometryCatalog geometry,
            X6MaterialPlan materials,
            X6VariantEffectCatalog effects) {
        selectionPlan = Objects.requireNonNull(selectionPlan, "selectionPlan");
        geometry = Objects.requireNonNull(geometry, "geometry");
        materials = Objects.requireNonNull(materials, "materials");
        effects = Objects.requireNonNull(effects, "effects");
        BlendModelKey modelKey = selectionPlan.modelKey();
        long generation = selectionPlan.generation();
        List<X6Diagnostic> diagnostics = new ArrayList<>();
        if (!modelKey.equals(geometry.modelKey()) || generation != geometry.generation()
                || !modelKey.equals(materials.modelKey()) || generation != materials.generation()
                || !modelKey.equals(effects.modelKey()) || generation != effects.generation()) {
            diagnostics.add(X6Diagnostic.error(
                    X6DiagnosticCode.GENERATION_MISMATCH,
                    modelKey,
                    generation,
                    null,
                    "Variant selection, geometry, material plan, and effects must belong to the exact same model generation"));
            return X6PlanResult.failure(ordered(diagnostics));
        }
        materials.collectCoverageDiagnostics(geometry, diagnostics);

        Map<BlendResourceId, Map<X6VariantKind, X6VariantDefinition>> selectedByPart = new LinkedHashMap<>();
        for (X6VariantSelection selection : selectionPlan.selections()) {
            if (selection.variantId().isEmpty()) {
                continue;
            }
            BlendResourceId variantId = selection.variantId().orElseThrow();
            X6VariantDefinition definition = selectionPlan.variantsById().get(variantId);
            if (definition == null) {
                diagnostics.add(X6Diagnostic.error(
                        X6DiagnosticCode.VARIANT_UNKNOWN,
                        modelKey,
                        generation,
                        variantId,
                        "Frozen X6 selection references no known variant"));
                continue;
            }
            if (definition.kind() != X6VariantKind.EQUIPMENT && !geometry.bindings().containsKey(definition.partId())) {
                diagnostics.add(X6Diagnostic.error(
                        X6DiagnosticCode.GEOMETRY_MISMATCH,
                        modelKey,
                        generation,
                        definition.partId(),
                        "Selected X6 variant targets no controlled prepared geometry part"));
                continue;
            }
            Map<X6VariantKind, X6VariantDefinition> perKind = selectedByPart.computeIfAbsent(
                    definition.partId(), ignored -> new LinkedHashMap<>());
            X6VariantDefinition prior = perKind.putIfAbsent(definition.kind(), definition);
            if (prior != null) {
                diagnostics.add(X6Diagnostic.error(
                        X6DiagnosticCode.VARIANT_CONFLICT,
                        modelKey,
                        generation,
                        definition.partId(),
                        "Two selected X6 " + definition.kind() + " effects target one part; no lexical overwrite is permitted"));
            }
        }
        for (Map.Entry<BlendResourceId, Map<X6VariantKind, X6VariantDefinition>> entry : selectedByPart.entrySet()) {
            if (entry.getValue().containsKey(X6VariantKind.EQUIPMENT) && entry.getValue().size() > 1) {
                diagnostics.add(X6Diagnostic.error(
                        X6DiagnosticCode.VARIANT_CONFLICT,
                        modelKey,
                        generation,
                        entry.getKey(),
                        "Equipment is a separately delegated prepared snapshot and cannot overlap a geometry-part effect"));
            }
        }
        if (hasError(diagnostics)) {
            return X6PlanResult.failure(ordered(diagnostics));
        }

        Map<BlendResourceId, X6DrawPrimitive> draws = new LinkedHashMap<>();
        for (Map.Entry<BlendResourceId, X6GeometryBinding> entry : geometry.bindings().entrySet()) {
            BlendResourceId partId = entry.getKey();
            draws.put(partId, new X6DrawPrimitive(partId, entry.getValue(), materials.material(partId), 0xFFFFFFFF));
        }
        List<ModelRenderSnapshot> equipment = new ArrayList<>();
        for (BlendResourceId partId : selectedByPart.keySet().stream().sorted(Comparator.comparing(BlendResourceId::value)).toList()) {
            Map<X6VariantKind, X6VariantDefinition> perKind = selectedByPart.get(partId);
            X6VariantDefinition equipmentDefinition = perKind.get(X6VariantKind.EQUIPMENT);
            if (equipmentDefinition != null) {
                applyEquipment(modelKey, generation, equipmentDefinition, effects, equipment, diagnostics);
                continue;
            }
            X6DrawPrimitive draw = draws.get(partId);
            if (draw == null) {
                diagnostics.add(X6Diagnostic.error(
                        X6DiagnosticCode.GEOMETRY_MISMATCH,
                        modelKey,
                        generation,
                        partId,
                        "A non-equipment X6 effect lost its controlled base draw before compilation"));
                continue;
            }
            draw = applyMesh(modelKey, generation, perKind.get(X6VariantKind.MESH), effects, geometry, draw, diagnostics);
            draw = applyMaterial(modelKey, generation, perKind.get(X6VariantKind.MATERIAL), effects, draw, diagnostics);
            draw = applySkin(modelKey, generation, perKind.get(X6VariantKind.SKIN), effects, draw, diagnostics);
            draw = applyDamage(modelKey, generation, perKind.get(X6VariantKind.DAMAGE_STAGE), effects, draw, diagnostics);
            X6VariantDefinition visibilityDefinition = perKind.get(X6VariantKind.PART_VISIBILITY);
            if (visibilityDefinition != null && !visible(modelKey, generation, visibilityDefinition, effects, diagnostics)) {
                draws.remove(partId);
            } else {
                draws.put(partId, draw);
            }
        }
        if (hasError(diagnostics)) {
            return X6PlanResult.failure(ordered(diagnostics));
        }
        return X6PlanResult.success(
                new X6VariantApplicationPlan(modelKey, generation, List.copyOf(draws.values()), List.copyOf(equipment)),
                ordered(diagnostics));
    }

    private static X6DrawPrimitive applyMesh(
            BlendModelKey modelKey,
            long generation,
            X6VariantDefinition definition,
            X6VariantEffectCatalog effects,
            X6PreparedGeometryCatalog geometry,
            X6DrawPrimitive draw,
            List<X6Diagnostic> diagnostics) {
        if (definition == null) {
            return draw;
        }
        X6VariantEffect effect = effectFor(modelKey, generation, definition, effects, diagnostics);
        if (!(effect instanceof X6VariantEffect.Mesh mesh)) {
            typeMismatch(modelKey, generation, definition, diagnostics);
            return draw;
        }
        X6GeometryBinding replacement;
        try {
            replacement = geometry.binding(mesh.replacementPartId());
        } catch (RuntimeException exception) {
            diagnostics.add(X6Diagnostic.error(
                    X6DiagnosticCode.GEOMETRY_MISMATCH,
                    modelKey,
                    generation,
                    mesh.replacementPartId(),
                    "A mesh effect must reference a controlled part from this exact X6 geometry catalog"));
            return draw;
        }
        if (!compatibleMeshBinding(draw.binding(), replacement)) {
            diagnostics.add(X6Diagnostic.error(
                    X6DiagnosticCode.GEOMETRY_MISMATCH,
                    modelKey,
                    generation,
                    definition.variantId(),
                    "A mesh replacement must retain the target's skinning mode, prepared node, and skinned skin binding"));
            return draw;
        }
        return new X6DrawPrimitive(draw.partId(), replacement, draw.material(), draw.argbTint());
    }

    private static X6DrawPrimitive applyMaterial(
            BlendModelKey modelKey,
            long generation,
            X6VariantDefinition definition,
            X6VariantEffectCatalog effects,
            X6DrawPrimitive draw,
            List<X6Diagnostic> diagnostics) {
        if (definition == null) {
            return draw;
        }
        X6VariantEffect effect = effectFor(modelKey, generation, definition, effects, diagnostics);
        if (!(effect instanceof X6VariantEffect.Material material)) {
            typeMismatch(modelKey, generation, definition, diagnostics);
            return draw;
        }
        if (!standardMaterial(modelKey, generation, definition.variantId(), material.material(), diagnostics)) {
            return draw;
        }
        return new X6DrawPrimitive(draw.partId(), draw.binding(), material.material(), draw.argbTint());
    }

    private static X6DrawPrimitive applySkin(
            BlendModelKey modelKey,
            long generation,
            X6VariantDefinition definition,
            X6VariantEffectCatalog effects,
            X6DrawPrimitive draw,
            List<X6Diagnostic> diagnostics) {
        if (definition == null) {
            return draw;
        }
        X6VariantEffect effect = effectFor(modelKey, generation, definition, effects, diagnostics);
        if (!(effect instanceof X6VariantEffect.Skin skin)) {
            typeMismatch(modelKey, generation, definition, diagnostics);
            return draw;
        }
        RenderMaterial material = draw.material();
        return new X6DrawPrimitive(
                draw.partId(),
                draw.binding(),
                new RenderMaterial(
                        skin.textureId(),
                        material.layer(),
                        material.emissive(),
                        material.doubleSided(),
                        material.argbTint(),
                        material.missingModelMaterial()),
                draw.argbTint());
    }

    private static X6DrawPrimitive applyDamage(
            BlendModelKey modelKey,
            long generation,
            X6VariantDefinition definition,
            X6VariantEffectCatalog effects,
            X6DrawPrimitive draw,
            List<X6Diagnostic> diagnostics) {
        if (definition == null) {
            return draw;
        }
        X6VariantEffect effect = effectFor(modelKey, generation, definition, effects, diagnostics);
        if (!(effect instanceof X6VariantEffect.DamageStage damage)) {
            typeMismatch(modelKey, generation, definition, diagnostics);
            return draw;
        }
        return new X6DrawPrimitive(draw.partId(), draw.binding(), draw.material(), multiplyArgb(draw.argbTint(), damage.argbTint()));
    }

    private static boolean visible(
            BlendModelKey modelKey,
            long generation,
            X6VariantDefinition definition,
            X6VariantEffectCatalog effects,
            List<X6Diagnostic> diagnostics) {
        X6VariantEffect effect = effectFor(modelKey, generation, definition, effects, diagnostics);
        if (!(effect instanceof X6VariantEffect.PartVisibility visibility)) {
            typeMismatch(modelKey, generation, definition, diagnostics);
            return true;
        }
        return visibility.visible();
    }

    private static void applyEquipment(
            BlendModelKey modelKey,
            long generation,
            X6VariantDefinition definition,
            X6VariantEffectCatalog effects,
            List<ModelRenderSnapshot> equipment,
            List<X6Diagnostic> diagnostics) {
        X6VariantEffect effect = effectFor(modelKey, generation, definition, effects, diagnostics);
        if (!(effect instanceof X6VariantEffect.Equipment attachment)) {
            typeMismatch(modelKey, generation, definition, diagnostics);
            return;
        }
        if (attachment.snapshot().generation() != generation) {
            diagnostics.add(X6Diagnostic.error(
                    X6DiagnosticCode.GENERATION_MISMATCH,
                    modelKey,
                    generation,
                    definition.variantId(),
                    "Equipment variant must retain a snapshot from the exact active generation"));
            return;
        }
        equipment.add(attachment.snapshot());
    }

    private static X6VariantEffect effectFor(
            BlendModelKey modelKey,
            long generation,
            X6VariantDefinition definition,
            X6VariantEffectCatalog effects,
            List<X6Diagnostic> diagnostics) {
        try {
            return effects.effect(definition.variantId());
        } catch (RuntimeException exception) {
            diagnostics.add(X6Diagnostic.error(
                    X6DiagnosticCode.VARIANT_UNKNOWN,
                    modelKey,
                    generation,
                    definition.variantId(),
                    "Selected X6 variant has no prepared code-side effect"));
            return null;
        }
    }

    private static boolean compatibleMeshBinding(X6GeometryBinding target, X6GeometryBinding replacement) {
        if (target.skinned() != replacement.skinned() || target.nodeIndex() != replacement.nodeIndex()) {
            return false;
        }
        if (target instanceof X6GeometryBinding.SkinnedBinding targetSkinned
                && replacement instanceof X6GeometryBinding.SkinnedBinding replacementSkinned) {
            return targetSkinned.primitive().skinIndex() == replacementSkinned.primitive().skinIndex();
        }
        return true;
    }

    private static boolean standardMaterial(
            BlendModelKey modelKey,
            long generation,
            BlendResourceId subject,
            RenderMaterial material,
            List<X6Diagnostic> diagnostics) {
        try {
            Minecraft2612StaticRigidRenderBackend.renderTypePathFor(material);
            return true;
        } catch (RuntimeException exception) {
            diagnostics.add(X6Diagnostic.error(
                    X6DiagnosticCode.MATERIAL_UNSUPPORTED,
                    modelKey,
                    generation,
                    subject,
                    "Material variant lacks an exact standard 26.1.2 public path"));
            return false;
        }
    }

    private static void typeMismatch(
            BlendModelKey modelKey, long generation, X6VariantDefinition definition, List<X6Diagnostic> diagnostics) {
        diagnostics.add(X6Diagnostic.error(
                X6DiagnosticCode.GEOMETRY_MISMATCH,
                modelKey,
                generation,
                definition.variantId(),
                "Prepared X6 effect type does not match selected variant kind " + definition.kind()));
    }

    private static boolean hasError(List<X6Diagnostic> diagnostics) {
        return diagnostics.stream().anyMatch(value -> value.severity() == X6DiagnosticSeverity.ERROR);
    }

    private static List<X6Diagnostic> ordered(List<X6Diagnostic> diagnostics) {
        return diagnostics.stream()
                .sorted(Comparator.comparing((X6Diagnostic value) -> value.code().code())
                        .thenComparing(value -> value.subjectId().map(BlendResourceId::value).orElse(""))
                        .thenComparing(X6Diagnostic::message))
                .toList();
    }

    private static int multiplyArgb(int left, int right) {
        return multiplyChannel(left >>> 24, right >>> 24) << 24
                | multiplyChannel(left >>> 16 & 0xFF, right >>> 16 & 0xFF) << 16
                | multiplyChannel(left >>> 8 & 0xFF, right >>> 8 & 0xFF) << 8
                | multiplyChannel(left & 0xFF, right & 0xFF);
    }

    private static int multiplyChannel(int left, int right) {
        return left * right / 255;
    }
}
