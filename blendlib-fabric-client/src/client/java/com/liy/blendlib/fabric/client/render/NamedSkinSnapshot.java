package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.api.BlendResourceId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Extraction-captured catalog selection fenced by exact prepared handle identity. */
final class NamedSkinSnapshot {
    private final ModelRenderHandle handle;
    private final Optional<BlendResourceId> selected;
    private final List<RenderMaterial> materials;
    private final Optional<String> diagnostic;

    private NamedSkinSnapshot(ModelRenderHandle handle, Optional<BlendResourceId> selected,
            List<RenderMaterial> materials, Optional<String> diagnostic) {
        this.handle = handle;
        this.selected = selected;
        this.materials = materials;
        this.diagnostic = diagnostic.map(message -> message.length() <= 1024
                ? message : message.substring(0, 1021) + "...");
    }

    static NamedSkinSnapshot capture(ModelRenderHandle handle, Optional<BlendResourceId> selected) {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(selected, "selected");
        if (handle.missingModel() || selected.isEmpty()) {
            return new NamedSkinSnapshot(handle, Optional.empty(), List.of(), Optional.empty());
        }
        var id = selected.orElseThrow();
        var catalog = handle.namedSkins();
        var materials = catalog.materials().get(id);
        if (materials != null) return new NamedSkinSnapshot(handle, selected, materials, Optional.empty());
        String diagnostic = catalog.diagnostics().getOrDefault(id,
                "Unknown named skin " + id.value() + "; using authored materials");
        return new NamedSkinSnapshot(handle, selected, List.of(), Optional.of(diagnostic));
    }

    void requireCompatible(ModelRenderHandle source) {
        if (handle != source) throw new IllegalArgumentException("Named skin belongs to another prepared handle");
    }

    RenderMaterial material(int index, RenderMaterial authored) {
        return materials.isEmpty() ? authored : materials.get(index);
    }

    Optional<BlendResourceId> selected() { return selected; }
    Optional<String> diagnostic() { return diagnostic; }
}
