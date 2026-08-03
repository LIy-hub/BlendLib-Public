package com.liy.blendlib.core.tooling;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.diagnostic.DiagnosticSeverity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Immutable result that renders deterministically without a JSON dependency. */
public final class AssetValidationResult {
    private static final Comparator<AssetValidationDiagnostic> DIAGNOSTIC_ORDER = Comparator
            .comparingInt((AssetValidationDiagnostic item) -> switch (item.severity()) {
                case ERROR -> 0;
                case WARN -> 1;
                case INFO -> 2;
            })
            .thenComparing(AssetValidationDiagnostic::code)
            .thenComparing(AssetValidationDiagnostic::location)
            .thenComparing(AssetValidationDiagnostic::message);

    private final BlendResourceId modelKey;
    private final List<AssetValidationDiagnostic> diagnostics;
    private final Map<String, Long> counts;

    AssetValidationResult(BlendResourceId modelKey, List<AssetValidationDiagnostic> diagnostics, Map<String, Long> counts) {
        this.modelKey = Objects.requireNonNull(modelKey, "modelKey");
        var orderedDiagnostics = new ArrayList<>(Objects.requireNonNull(diagnostics, "diagnostics"));
        orderedDiagnostics.sort(DIAGNOSTIC_ORDER);
        this.diagnostics = List.copyOf(orderedDiagnostics);
        this.counts = Map.copyOf(new TreeMap<>(Objects.requireNonNull(counts, "counts")));
    }

    public BlendResourceId modelKey() {
        return modelKey;
    }

    public List<AssetValidationDiagnostic> diagnostics() {
        return diagnostics;
    }

    public Map<String, Long> counts() {
        return counts;
    }

    public boolean valid() {
        return diagnostics.stream().noneMatch(item -> item.severity() == DiagnosticSeverity.ERROR);
    }

    public String toCanonicalJson() {
        List<Map<String, Object>> renderedDiagnostics = diagnostics.stream()
                .map(item -> Map.<String, Object>of(
                        "code", item.code(),
                        "location", item.location(),
                        "message", item.message(),
                        "severity", item.severity().name()))
                .toList();
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("counts", counts);
        root.put("diagnostics", renderedDiagnostics);
        root.put("format", "blendlib-x5-cli-validation-v1");
        root.put("model_key", modelKey.value());
        root.put("valid", valid());
        return ToolingJson.canonical(root);
    }

    public String toText() {
        StringBuilder text = new StringBuilder();
        text.append(valid() ? "VALID" : "INVALID").append(' ').append(modelKey.value()).append('\n');
        for (AssetValidationDiagnostic item : diagnostics) {
            text.append(item.severity()).append(' ').append(item.code()).append(' ')
                    .append(item.location()).append(" - ").append(item.message()).append('\n');
        }
        for (Map.Entry<String, Long> count : new TreeMap<>(counts).entrySet()) {
            text.append("COUNT ").append(count.getKey()).append('=').append(count.getValue()).append('\n');
        }
        return text.toString();
    }
}
