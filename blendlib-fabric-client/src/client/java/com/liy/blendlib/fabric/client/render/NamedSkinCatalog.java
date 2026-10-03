package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.api.BlendResourceId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable generation-prepared texture skins, in exact prepared primitive order. */
public final class NamedSkinCatalog {
    public static final int MAX_PRIMITIVE_MATERIAL_ENTRIES = 65_536;
    private static final NamedSkinCatalog EMPTY = new NamedSkinCatalog(Map.of(), Map.of());
    private final Map<BlendResourceId, List<RenderMaterial>> materials;
    private final Map<BlendResourceId, String> diagnostics;

    private NamedSkinCatalog(Map<BlendResourceId, List<RenderMaterial>> materials,
            Map<BlendResourceId, String> diagnostics) {
        this.materials = ordered(materials);
        Map<BlendResourceId, String> bounded = new LinkedHashMap<>();
        diagnostics.forEach((id, message) -> bounded.put(id,
                message.length() <= 1024 ? message : message.substring(0, 1021) + "..."));
        this.diagnostics = ordered(bounded);
    }

    private static <T> Map<BlendResourceId, T> ordered(Map<BlendResourceId, T> source) {
        Map<BlendResourceId, T> result = new LinkedHashMap<>();
        source.keySet().stream().sorted(Comparator.comparing(BlendResourceId::value))
                .forEach(id -> result.put(id, source.get(id)));
        return Collections.unmodifiableMap(result);
    }

    public static NamedSkinCatalog empty() { return EMPTY; }

    /** Valid skins only; both the map and each primitive-ordered list are immutable. */
    public Map<BlendResourceId, List<RenderMaterial>> materials() { return materials; }

    /** Per-skin preparation failures; selection falls back atomically to authored materials. */
    public Map<BlendResourceId, String> diagnostics() { return diagnostics; }

    static NamedSkinCatalog prepare(List<RenderMaterial> authored, List<String> slots,
            Map<BlendResourceId, Map<String, BlendResourceId>> definitions,
            Map<BlendResourceId, String> invalidDiagnostics) {
        return prepare(authored, slots, new HashSet<>(slots), definitions, invalidDiagnostics);
    }

    static NamedSkinCatalog prepare(List<RenderMaterial> authored, List<String> slots,
            java.util.Set<String> authoredSlots,
            Map<BlendResourceId, Map<String, BlendResourceId>> definitions,
            Map<BlendResourceId, String> invalidDiagnostics) {
        Objects.requireNonNull(definitions, "definitions");
        Map<BlendResourceId, String> diagnostics = new LinkedHashMap<>(Map.copyOf(invalidDiagnostics));
        if (authored.size() != slots.size()) {
            throw new IllegalArgumentException("Named skin slot metadata must match prepared primitive count");
        }
        if (definitions.size() > BlendLibModelSkins.MAX_SKINS_PER_MODEL) {
            definitions.keySet().forEach(id -> diagnostics.put(id,
                    "Named skin catalog exceeds 32 skins per model; using authored materials"));
            return new NamedSkinCatalog(Map.of(), diagnostics);
        }
        Map<BlendResourceId, Map<String, BlendResourceId>> valid = new LinkedHashMap<>();
        var knownSlots = java.util.Set.copyOf(authoredSlots);
        definitions.forEach((id, replacements) -> {
            Objects.requireNonNull(id, "skin id");
            if (replacements.isEmpty() || replacements.size() > BlendLibModelSkins.MAX_REPLACEMENTS_PER_SKIN) {
                diagnostics.put(id, "Named skin must contain 1..64 slot replacements; using authored materials");
                return;
            }
            var copy = Map.copyOf(replacements);
            var unknown = copy.keySet().stream().filter(slot -> !knownSlots.contains(slot)).sorted().toList();
            if (!unknown.isEmpty()) {
                diagnostics.put(id, "Named skin " + id.value() + " references unknown material slots: " + unknown);
            } else if (!diagnostics.containsKey(id)) {
                valid.put(id, copy);
            }
        });
        if ((long) authored.size() * valid.size() > MAX_PRIMITIVE_MATERIAL_ENTRIES) {
            valid.keySet().forEach(id -> diagnostics.put(id, "Named skin catalog exceeds the "
                    + MAX_PRIMITIVE_MATERIAL_ENTRIES + " primitive-material entry budget; using authored materials"));
            return new NamedSkinCatalog(Map.of(), diagnostics);
        }
        Map<BlendResourceId, List<RenderMaterial>> compiled = new LinkedHashMap<>();
        valid.forEach((id, replacements) -> {
            List<RenderMaterial> result = new ArrayList<>(authored.size());
            for (int i = 0; i < authored.size(); i++) {
                var texture = replacements.get(slots.get(i));
                result.add(texture == null ? authored.get(i) : TextureOnlyMaterial.replace(authored.get(i), texture));
            }
            compiled.put(id, List.copyOf(result));
        });
        return compiled.isEmpty() && diagnostics.isEmpty() ? EMPTY : new NamedSkinCatalog(compiled, diagnostics);
    }
}
