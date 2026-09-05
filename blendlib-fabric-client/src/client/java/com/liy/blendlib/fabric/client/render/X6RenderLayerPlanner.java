package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Resolves all eight ordered X6 layer selectors only while a model generation is prepared. */
public final class X6RenderLayerPlanner {
    private static final Comparator<X6LayerEntry> ORDER = Comparator
            .comparing((X6LayerEntry value) -> value.phase().ordinal())
            .thenComparingInt(X6LayerEntry::order)
            .thenComparing(value -> value.layerId().value());

    private X6RenderLayerPlanner() {
    }

    /**
     * Resolves only controlled targets, explicit overrides, and fixed presentation behavior.
     *
     * <p>Any absent material is intentional inheritance, not a catalog-base fallback. The factory
     * resolves it from the final variant draw after mesh/material/skin composition and freezes the
     * final route, culling, light, tint, and texture in {@link X6PreparedLayerSubmission}.</p>
     */
    public static X6PlanResult<X6RenderLayerPlan> prepare(
            BlendModelKey modelKey,
            long generation,
            List<X6LayerEntry> entries,
            X6PreparedGeometryCatalog geometry) {
        modelKey = Objects.requireNonNull(modelKey, "modelKey");
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        geometry = Objects.requireNonNull(geometry, "geometry");
        if (generation < 0L) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
        List<X6Diagnostic> diagnostics = new ArrayList<>();
        if (!geometry.modelKey().equals(modelKey) || geometry.generation() != generation) {
            diagnostics.add(X6Diagnostic.error(
                    X6DiagnosticCode.GENERATION_MISMATCH,
                    modelKey,
                    generation,
                    null,
                    "X6 layer planning cannot mix geometry from another model or generation"));
        }
        if (entries.size() > X6Ids.MAX_LAYERS) {
            diagnostics.add(X6Diagnostic.error(
                    X6DiagnosticCode.LAYER_LIMIT,
                    modelKey,
                    generation,
                    null,
                    "X6 layer count exceeds " + X6Ids.MAX_LAYERS));
        }
        List<X6ResolvedRenderLayer> resolved = new ArrayList<>();
        Set<BlendResourceId> ids = new HashSet<>();
        Set<String> placement = new HashSet<>();
        for (X6LayerEntry entry : entries.stream().sorted(ORDER).toList()) {
            if (!ids.add(entry.layerId())) {
                diagnostics.add(X6Diagnostic.error(
                        X6DiagnosticCode.LAYER_DUPLICATE,
                        modelKey,
                        generation,
                        entry.layerId(),
                        "Duplicate X6 layer id"));
                continue;
            }
            resolve(modelKey, generation, entry, geometry, placement, resolved, diagnostics);
        }
        List<X6Diagnostic> ordered = ordered(diagnostics);
        if (ordered.stream().anyMatch(value -> value.severity() == X6DiagnosticSeverity.ERROR)) {
            return X6PlanResult.failure(ordered);
        }
        return X6PlanResult.success(new X6RenderLayerPlan(modelKey, generation, resolved), ordered);
    }

    private static void resolve(
            BlendModelKey modelKey,
            long generation,
            X6LayerEntry entry,
            X6PreparedGeometryCatalog geometry,
            Set<String> placement,
            List<X6ResolvedRenderLayer> resolved,
            List<X6Diagnostic> diagnostics) {
        X6RenderPhase expected = expectedPhase(entry.type());
        if (entry.phase() != expected) {
            diagnostics.add(X6Diagnostic.error(
                    X6DiagnosticCode.LAYER_CONFLICT,
                    modelKey,
                    generation,
                    entry.layerId(),
                    entry.type() + " must use phase " + expected));
            return;
        }
        if (entry.type() == X6LayerType.ATTACHMENT) {
            resolveAttachment(modelKey, generation, entry, placement, resolved, diagnostics);
            return;
        }
        Optional<BlendResourceId> partId = entry.target().partId();
        if (partId.isEmpty() || !geometry.bindings().containsKey(partId.orElseThrow())) {
            diagnostics.add(X6Diagnostic.error(
                    X6DiagnosticCode.LAYER_TARGET_MISSING,
                    modelKey,
                    generation,
                    entry.layerId(),
                    "X6 layer target must resolve to a controlled prepared part before submit"));
            return;
        }
        Optional<BlendResourceId> canonicalBone = Optional.empty();
        if (entry.type() == X6LayerType.PER_BONE_PART_TEXTURE) {
            if (entry.target().boneId().isEmpty()
                    || !geometry.resolvesBoneForPart(entry.target().boneId().orElseThrow(), partId.orElseThrow())) {
                diagnostics.add(X6Diagnostic.error(
                        X6DiagnosticCode.LAYER_TARGET_MISSING,
                        modelKey,
                        generation,
                        entry.layerId(),
                        "A per-bone X6 layer requires a canonical skinned bone binding for the selected part"));
                return;
            }
            canonicalBone = entry.target().boneId();
        }
        String placementKey = entry.phase() + ":" + entry.order() + ":" + partId.orElseThrow().value();
        if (!placement.add(placementKey)) {
            diagnostics.add(X6Diagnostic.error(
                    X6DiagnosticCode.LAYER_CONFLICT,
                    modelKey,
                    generation,
                    entry.layerId(),
                    "Two X6 layers occupy the same phase/order/part; insertion order is not a winner"));
            return;
        }
        if (!validateExplicitMaterial(entry, modelKey, generation, diagnostics)) {
            return;
        }
        resolved.add(new X6ResolvedRenderLayer(
                entry.layerId(),
                entry.type(),
                provisionalSemantics(entry),
                partId,
                canonicalBone,
                entry.preparedMaterial(),
                Optional.empty(),
                presentationFor(entry.type()),
                entry.presentationArgb()));
    }

    private static void resolveAttachment(
            BlendModelKey modelKey,
            long generation,
            X6LayerEntry entry,
            Set<String> placement,
            List<X6ResolvedRenderLayer> resolved,
            List<X6Diagnostic> diagnostics) {
        ModelRenderSnapshot attachment = entry.attachmentSnapshot().orElse(null);
        if (attachment == null || attachment.generation() != generation) {
            diagnostics.add(X6Diagnostic.error(
                    X6DiagnosticCode.GENERATION_MISMATCH,
                    modelKey,
                    generation,
                    entry.layerId(),
                    "An X6 attachment must hold an already prepared snapshot from the exact active generation"));
            return;
        }
        String placementKey = entry.phase() + ":" + entry.order() + ":attachment";
        if (!placement.add(placementKey)) {
            diagnostics.add(X6Diagnostic.error(
                    X6DiagnosticCode.LAYER_CONFLICT,
                    modelKey,
                    generation,
                    entry.layerId(),
                    "Two X6 attachments occupy the same phase/order"));
            return;
        }
        resolved.add(new X6ResolvedRenderLayer(
                entry.layerId(),
                entry.type(),
                new X6LayerSemantics(
                        entry.phase(),
                        entry.order(),
                        X6BlendSemantic.INHERIT,
                        X6CullingSemantic.INHERIT,
                        X6LightSemantic.INHERIT,
                        X6TextureTarget.ATTACHMENT_SNAPSHOT),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(attachment),
                X6LayerPresentation.NONE,
                entry.presentationArgb()));
    }

    private static boolean validateExplicitMaterial(
            X6LayerEntry entry,
            BlendModelKey modelKey,
            long generation,
            List<X6Diagnostic> diagnostics) {
        if (entry.preparedMaterial().isEmpty()) {
            return true;
        }
        try {
            Minecraft2612StaticRigidRenderBackend.renderTypePathFor(entry.preparedMaterial().orElseThrow());
            return true;
        } catch (RuntimeException exception) {
            diagnostics.add(X6Diagnostic.error(
                    X6DiagnosticCode.MATERIAL_UNSUPPORTED,
                    modelKey,
                    generation,
                    entry.layerId(),
                    "X6 explicit layer material has no descriptor-equivalent public standard path"));
            return false;
        }
    }

    private static X6LayerSemantics provisionalSemantics(X6LayerEntry entry) {
        RenderMaterial material = entry.preparedMaterial().orElse(null);
        X6BlendSemantic blend = material == null ? X6BlendSemantic.INHERIT : blendFor(material);
        X6CullingSemantic culling = material == null ? X6CullingSemantic.INHERIT : cullingFor(material);
        X6LightSemantic light = entry.type() == X6LayerType.GLOW
                ? X6LightSemantic.FULL_BRIGHT
                : material == null ? X6LightSemantic.INHERIT : lightFor(material);
        X6TextureTarget texture = switch (entry.type()) {
            case SECONDARY_TEXTURE -> X6TextureTarget.SECONDARY_TEXTURE;
            case PER_BONE_PART_TEXTURE -> X6TextureTarget.PART_TEXTURE;
            default -> X6TextureTarget.BASE_TEXTURE;
        };
        return new X6LayerSemantics(entry.phase(), entry.order(), blend, culling, light, texture);
    }

    static X6LayerSemantics finalSemantics(X6ResolvedRenderLayer layer, RenderMaterial material) {
        return new X6LayerSemantics(
                layer.semantics().phase(),
                layer.semantics().order(),
                blendFor(material),
                cullingFor(material),
                layer.type() == X6LayerType.GLOW || material.emissive()
                        ? X6LightSemantic.FULL_BRIGHT
                        : X6LightSemantic.PACKED_LIGHT,
                layer.semantics().textureTarget());
    }

    private static X6BlendSemantic blendFor(RenderMaterial material) {
        return switch (material.layer()) {
            case SOLID -> X6BlendSemantic.OPAQUE;
            case CUTOUT -> X6BlendSemantic.CUTOUT;
            case TRANSLUCENT -> X6BlendSemantic.TRANSLUCENT;
        };
    }

    private static X6CullingSemantic cullingFor(RenderMaterial material) {
        return material.doubleSided() ? X6CullingSemantic.NO_CULL : X6CullingSemantic.CULL;
    }

    private static X6LightSemantic lightFor(RenderMaterial material) {
        return material.emissive() ? X6LightSemantic.FULL_BRIGHT : X6LightSemantic.PACKED_LIGHT;
    }

    private static X6LayerPresentation presentationFor(X6LayerType type) {
        return switch (type) {
            case OUTLINE -> X6LayerPresentation.OUTLINE_SHELL;
            case SHADOW -> X6LayerPresentation.SHADOW_FLATTEN;
            case DAMAGE_FLASH -> X6LayerPresentation.DAMAGE_FLASH;
            default -> X6LayerPresentation.NONE;
        };
    }

    private static X6RenderPhase expectedPhase(X6LayerType type) {
        return switch (type) {
            case GLOW, SECONDARY_TEXTURE, PER_BONE_PART_TEXTURE -> X6RenderPhase.POST_BASE;
            case OVERLAY, DAMAGE_FLASH -> X6RenderPhase.OVERLAY;
            case ATTACHMENT -> X6RenderPhase.ATTACHMENT;
            case OUTLINE, SHADOW -> X6RenderPhase.PRESENTATION;
        };
    }

    private static List<X6Diagnostic> ordered(List<X6Diagnostic> diagnostics) {
        return diagnostics.stream()
                .sorted(Comparator.comparing((X6Diagnostic value) -> value.code().code())
                        .thenComparing(value -> value.subjectId().map(BlendResourceId::value).orElse(""))
                        .thenComparing(X6Diagnostic::message))
                .toList();
    }
}
