package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.descriptor.MaterialDefinition;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Frozen adapter-side material map for X6 mesh/primitive parts.
 *
 * <p>It delegates standard semantics to the established ADR-014/019 mapper. Complex PBR, custom
 * shaders, and additive paths are deliberately not enabled: they fail closed before publication.
 */
public final class X6MaterialPlan {
    private final BlendModelKey modelKey;
    private final long generation;
    private final Map<BlendResourceId, RenderMaterial> materials;

    private X6MaterialPlan(BlendModelKey modelKey, long generation, Map<BlendResourceId, RenderMaterial> materials) {
        this.modelKey = Objects.requireNonNull(modelKey, "modelKey");
        this.generation = generation;
        this.materials = X6Ids.immutableOrderedMap(materials);
    }

    /** Resolves every mesh/primitive material during preparation, never in render submit. */
    public static X6PlanResult<X6MaterialPlan> prepare(
            BlendModelKey modelKey, long generation, Map<BlendResourceId, X6MaterialIntent> intents) {
        BlendModelKey checkedModelKey = Objects.requireNonNull(modelKey, "modelKey");
        Objects.requireNonNull(intents, "intents");
        if (generation < 0L) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
        List<X6Diagnostic> diagnostics = new ArrayList<>();
        if (intents.isEmpty() || intents.size() > X6Ids.MAX_PARTS) {
            diagnostics.add(X6Diagnostic.error(
                    X6DiagnosticCode.VARIANT_LIMIT,
                    checkedModelKey,
                    generation,
                    null,
                    "X6 material part count must be 1.." + X6Ids.MAX_PARTS));
        }
        Map<BlendResourceId, RenderMaterial> resolved = new LinkedHashMap<>();
        intents.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().value()))
                .forEach(entry -> resolve(
                        checkedModelKey, generation, entry.getKey(), entry.getValue(), resolved, diagnostics));
        diagnostics.sort(Comparator.comparing((X6Diagnostic value) -> value.code().code())
                .thenComparing(value -> value.subjectId().map(BlendResourceId::value).orElse(""))
                .thenComparing(X6Diagnostic::message));
        if (diagnostics.stream().anyMatch(value -> value.severity() == X6DiagnosticSeverity.ERROR)) {
            return X6PlanResult.failure(diagnostics);
        }
        return X6PlanResult.success(new X6MaterialPlan(checkedModelKey, generation, resolved), diagnostics);
    }

    private static void resolve(
            BlendModelKey modelKey,
            long generation,
            BlendResourceId partId,
            X6MaterialIntent intent,
            Map<BlendResourceId, RenderMaterial> resolved,
            List<X6Diagnostic> diagnostics) {
        partId = X6Ids.requireId(partId, "partId");
        intent = Objects.requireNonNull(intent, "intent");
        if (intent.mode() == X6MaterialMode.COMPLEX_PBR || intent.mode() == X6MaterialMode.CUSTOM_SHADER) {
            diagnostics.add(X6Diagnostic.error(
                    X6DiagnosticCode.MATERIAL_UNSUPPORTED,
                    modelKey,
                    generation,
                    partId,
                    intent.mode() + " remains disabled by ADR-016; no custom pipeline is constructed"));
            return;
        }
        MaterialDefinition.Mode descriptorMode = switch (intent.mode()) {
            case OPAQUE -> MaterialDefinition.Mode.OPAQUE;
            case CUTOUT -> MaterialDefinition.Mode.CUTOUT;
            case TRANSLUCENT -> MaterialDefinition.Mode.TRANSLUCENT;
            case ADDITIVE -> MaterialDefinition.Mode.ADDITIVE;
            case COMPLEX_PBR, CUSTOM_SHADER -> throw new IllegalStateException("already rejected");
        };
        MaterialMapping mapping = MaterialRenderMapper.map(new MaterialDefinition(
                intent.textureId(), descriptorMode, intent.emissive(), intent.doubleSided(), intent.cutoutThreshold()));
        if (mapping instanceof MaterialMapping.Rejected rejected) {
            diagnostics.add(X6Diagnostic.error(
                    X6DiagnosticCode.MATERIAL_UNSUPPORTED,
                    modelKey,
                    generation,
                    partId,
                    rejected.reason().name() + ": " + rejected.message()));
            return;
        }
        resolved.put(partId, ((MaterialMapping.Supported) mapping).material());
    }

    public BlendModelKey modelKey() {
        return modelKey;
    }

    public long generation() {
        return generation;
    }

    public Map<BlendResourceId, RenderMaterial> materials() {
        return materials;
    }

    public RenderMaterial material(BlendResourceId partId) {
        RenderMaterial material = materials.get(Objects.requireNonNull(partId, "partId"));
        if (material == null) {
            throw new IllegalArgumentException("Unknown X6 material part: " + partId);
        }
        return material;
    }

    /**
     * Verifies that this immutable material map is the complete material authority for one exact
     * catalog. A compiler must call this before it can retain any draw; falling back to a primitive
     * material would otherwise hide a missing prepared-material decision.
     */
    void collectCoverageDiagnostics(
            X6PreparedGeometryCatalog geometry, List<X6Diagnostic> diagnostics) {
        Objects.requireNonNull(geometry, "geometry");
        Objects.requireNonNull(diagnostics, "diagnostics");
        if (!modelKey.equals(geometry.modelKey()) || generation != geometry.generation()) {
            diagnostics.add(X6Diagnostic.error(
                    X6DiagnosticCode.GENERATION_MISMATCH,
                    modelKey,
                    generation,
                    null,
                    "X6 material plan and geometry catalog must belong to the exact same model generation"));
            return;
        }
        for (BlendResourceId partId : geometry.bindings().keySet()) {
            if (!materials.containsKey(partId)) {
                diagnostics.add(X6Diagnostic.error(
                        X6DiagnosticCode.MATERIAL_UNSUPPORTED,
                        modelKey,
                        generation,
                        partId,
                        "Every prepared X6 geometry part needs one explicit standard material-plan entry"));
            }
        }
        for (BlendResourceId partId : materials.keySet()) {
            if (!geometry.bindings().containsKey(partId)) {
                diagnostics.add(X6Diagnostic.error(
                        X6DiagnosticCode.GEOMETRY_MISMATCH,
                        modelKey,
                        generation,
                        partId,
                        "X6 material plan names a part absent from the prepared geometry catalog"));
            }
        }
    }
}
