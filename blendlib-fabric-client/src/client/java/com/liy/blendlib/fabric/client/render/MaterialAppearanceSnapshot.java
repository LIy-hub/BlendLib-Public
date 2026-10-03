package com.liy.blendlib.fabric.client.render;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Extraction-only binding; its ordinal values are fenced by exact prepared handle identity. */
final class MaterialAppearanceSnapshot {
    private final ModelRenderHandle handle;
    private final List<MaterialSlotAppearance> primitives;
    private final List<String> unknownSlots;

    private MaterialAppearanceSnapshot(ModelRenderHandle handle, List<MaterialSlotAppearance> primitives,
            List<String> unknownSlots) {
        this.handle = handle;
        this.primitives = List.copyOf(primitives);
        this.unknownSlots = List.copyOf(unknownSlots);
    }

    static MaterialAppearanceSnapshot capture(ModelRenderHandle handle, Map<String, MaterialSlotAppearance> selection) {
        Map<String, MaterialSlotAppearance> copy = Map.copyOf(Objects.requireNonNull(selection, "selection"));
        for (String slot : copy.keySet()) {
            if (slot.isBlank()) throw new IllegalArgumentException("Material slot names must not be blank");
        }
        // Error-model geometry must always retain its authored diagnostic appearance.
        if (handle.missingModel()) return new MaterialAppearanceSnapshot(handle, List.of(), List.of());
        List<String> slots = List.copyOf(handle.materialSlots());
        int count = handle.skinned() ? handle.skinnedPrimitives().size() : handle.primitives().size();
        if (!slots.isEmpty() && slots.size() != count) {
            throw new IllegalArgumentException("Material slot metadata must match prepared primitive count");
        }
        List<String> unknown = copy.keySet().stream().filter(slot -> !slots.contains(slot)).sorted().toList();
        // Atomic fallback avoids partially recoloring an incompatible resource-pack generation.
        if (!unknown.isEmpty() || copy.isEmpty()) return new MaterialAppearanceSnapshot(handle, List.of(), unknown);
        return new MaterialAppearanceSnapshot(handle,
                slots.stream().map(slot -> copy.getOrDefault(slot, MaterialSlotAppearance.unchanged())).toList(), List.of());
    }

    void requireCompatible(ModelRenderHandle source) {
        if (handle != source) throw new IllegalArgumentException("Material appearance belongs to another prepared handle");
    }

    MaterialSlotAppearance primitive(int index) {
        return primitives.isEmpty() ? MaterialSlotAppearance.unchanged() : primitives.get(index);
    }

    List<String> unknownSlots() { return unknownSlots; }
}
