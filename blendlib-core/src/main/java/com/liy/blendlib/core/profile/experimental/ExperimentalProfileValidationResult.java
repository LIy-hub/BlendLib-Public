package com.liy.blendlib.core.profile.experimental;

import com.liy.blendlib.api.BlendResourceId;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable, deterministic validation-only result; it is not a runtime render asset. */
public record ExperimentalProfileValidationResult(
        BlendResourceId modelKey,
        ExperimentalDescriptor descriptor,
        int primitiveCount,
        int morphTargetCount,
        int cubicSplineSamplerCount,
        int vertexColorPrimitiveCount,
        int secondaryUvPrimitiveCount,
        List<BlendResourceId> negotiatedCapabilities,
        List<ExperimentalProfileDiagnostic> diagnostics) {
    private static final int MAX_CANONICAL_JSON_BYTES = 512 * 1024;

    public ExperimentalProfileValidationResult {
        modelKey = Objects.requireNonNull(modelKey, "modelKey");
        descriptor = Objects.requireNonNull(descriptor, "descriptor");
        if (primitiveCount < 0 || morphTargetCount < 0 || cubicSplineSamplerCount < 0
                || vertexColorPrimitiveCount < 0 || secondaryUvPrimitiveCount < 0) {
            throw new IllegalArgumentException("X9 validation counts must be non-negative");
        }
        negotiatedCapabilities = List.copyOf(
                Objects.requireNonNull(negotiatedCapabilities, "negotiatedCapabilities"));
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    }

    /**
     * Serializes every result and descriptor field with fixed key order, sorted
     * maps/lists, finite canonical numbers, bounded output, and JSON escaping.
     */
    public String canonicalJson() {
        StringBuilder json = new StringBuilder(2_048);
        json.append('{');
        field(json, "model_key", modelKey.value());
        json.append(',');
        field(json, "descriptor_id", descriptor.descriptorId().value());
        json.append(",\"descriptor\":{");
        numberField(json, "format_version", "2");
        json.append(',');
        field(json, "profile", descriptor.profile().serializedName());
        json.append(',');
        field(json, "mesh", descriptor.meshId().value());
        json.append(',');
        numberField(json, "units_per_block", finiteNumber(descriptor.unitsPerBlock()));
        json.append(",\"materials\":{");
        List<Map.Entry<String, ExperimentalMaterialDefinition>> materials =
                descriptor.materials().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .toList();
        for (int index = 0; index < materials.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            Map.Entry<String, ExperimentalMaterialDefinition> entry = materials.get(index);
            quoted(json, entry.getKey());
            json.append(':');
            appendMaterial(json, entry.getValue());
        }
        json.append("},\"capabilities\":{");
        List<ExperimentalCapabilityRequirement> requirements = new ArrayList<>();
        requirements.addAll(descriptor.requiredCapabilities());
        requirements.addAll(descriptor.optionalCapabilities());
        requirements.sort(Comparator.comparing(requirement -> requirement.id().value()));
        for (int index = 0; index < requirements.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            ExperimentalCapabilityRequirement requirement = requirements.get(index);
            quoted(json, requirement.id().value());
            json.append(":{");
            field(json, "requirement", requirement.required() ? "required" : "optional");
            json.append(',');
            field(json, "min_version", requirement.minInclusive().toString());
            json.append(',');
            field(json, "max_version", requirement.maxExclusive().toString());
            if (!requirement.required()) {
                json.append(',');
                field(json, "fallback", requirement.fallback().serializedName());
            }
            json.append('}');
        }
        json.append("}}");
        json.append(",\"counts\":{");
        numberField(json, "primitives", Integer.toString(primitiveCount));
        json.append(',');
        numberField(json, "morph_targets", Integer.toString(morphTargetCount));
        json.append(',');
        numberField(json, "cubic_spline_samplers", Integer.toString(cubicSplineSamplerCount));
        json.append(',');
        numberField(json, "vertex_color_primitives", Integer.toString(vertexColorPrimitiveCount));
        json.append(',');
        numberField(json, "secondary_uv_primitives", Integer.toString(secondaryUvPrimitiveCount));
        json.append("},\"negotiated_capabilities\":[");
        List<BlendResourceId> selected = negotiatedCapabilities.stream()
                .sorted(Comparator.comparing(BlendResourceId::value))
                .toList();
        for (int index = 0; index < selected.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            quoted(json, selected.get(index).value());
        }
        json.append("],\"diagnostics\":[");
        List<ExperimentalProfileDiagnostic> orderedDiagnostics = diagnostics.stream()
                .sorted(Comparator.comparing(ExperimentalProfileDiagnostic::code)
                        .thenComparing(ExperimentalProfileDiagnostic::location)
                        .thenComparing(ExperimentalProfileDiagnostic::message)
                        .thenComparing(ExperimentalProfileDiagnostic::fallback))
                .toList();
        for (int index = 0; index < orderedDiagnostics.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            ExperimentalProfileDiagnostic diagnostic = orderedDiagnostics.get(index);
            json.append('{');
            field(json, "severity", diagnostic.severity().name());
            json.append(',');
            field(json, "code", diagnostic.code());
            json.append(',');
            field(json, "location", diagnostic.location());
            json.append(',');
            field(json, "message", diagnostic.message());
            json.append(',');
            field(json, "fallback", diagnostic.fallback());
            json.append('}');
        }
        json.append("]}");

        String result = json.toString();
        if (result.getBytes(StandardCharsets.UTF_8).length > MAX_CANONICAL_JSON_BYTES) {
            throw new IllegalStateException("Canonical X9 validation summary exceeds its bounded output size");
        }
        return result;
    }

    private static void appendMaterial(StringBuilder json, ExperimentalMaterialDefinition material) {
        json.append('{');
        field(json, "base_color", material.baseColor().value());
        json.append(',');
        field(json, "mode", material.mode().serializedName());
        json.append(',');
        booleanField(json, "double_sided", material.doubleSided());
        json.append(',');
        numberField(json, "metallic_factor", finiteNumber(material.metallicFactor()));
        json.append(',');
        numberField(json, "roughness_factor", finiteNumber(material.roughnessFactor()));
        json.append(',');
        nullableResourceField(json, "normal_texture", material.normalTexture());
        json.append(',');
        nullableResourceField(json, "occlusion_texture", material.occlusionTexture());
        json.append(',');
        nullableResourceField(json, "emissive_texture", material.emissiveTexture());
        json.append(",\"emissive_factor\":[");
        for (int index = 0; index < material.emissiveFactor().size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append(finiteNumber(material.emissiveFactor().get(index)));
        }
        json.append("],\"alpha_cutoff\":");
        if (material.alphaCutoff() == null) {
            json.append("null");
        } else {
            json.append(finiteNumber(material.alphaCutoff()));
        }
        json.append('}');
    }

    private static void field(StringBuilder json, String key, String value) {
        quoted(json, key);
        json.append(':');
        quoted(json, value);
    }

    private static void numberField(StringBuilder json, String key, String value) {
        quoted(json, key);
        json.append(':').append(value);
    }

    private static void booleanField(StringBuilder json, String key, boolean value) {
        quoted(json, key);
        json.append(':').append(value);
    }

    private static void nullableResourceField(StringBuilder json, String key, BlendResourceId value) {
        quoted(json, key);
        json.append(':');
        if (value == null) {
            json.append("null");
        } else {
            quoted(json, value.value());
        }
    }

    private static String finiteNumber(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalStateException("Canonical JSON cannot contain a non-finite number");
        }
        return Double.toString(value);
    }

    private static void quoted(StringBuilder json, String value) {
        Objects.requireNonNull(value, "value");
        json.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (character < 0x20) {
                        json.append(String.format("\\u%04x", (int) character));
                    } else {
                        json.append(character);
                    }
                }
            }
        }
        json.append('"');
    }
}
