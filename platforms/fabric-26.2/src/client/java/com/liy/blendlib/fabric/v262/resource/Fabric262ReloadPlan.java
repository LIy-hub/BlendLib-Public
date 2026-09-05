package com.liy.blendlib.fabric.v262.resource;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.fabric.v262.diagnostic.Fabric262Diagnostic;
import com.liy.blendlib.fabric.v262.model.Fabric262PreparedModelHandle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable reload-only candidate payload before it becomes the active Fabric 26.2 generation.
 *
 * <p><strong>Platform target:</strong> Fabric on Minecraft 26.2. Preparation may perform resource
 * I/O and strict JSON/GLB decoding; application publishes this already-copied plan atomically so
 * render submission can never observe a partially decoded map.</p>
 *
 * @param generation non-negative planned generation
 * @param handles immutable strict key to prepared/fallback handle map
 * @param diagnostics immutable preparation diagnostics
 */
public record Fabric262ReloadPlan(
        long generation,
        Map<BlendModelKey, Fabric262PreparedModelHandle> handles,
        List<Fabric262Diagnostic> diagnostics) {
    /**
     * Validates and defensively sorts one immutable reload candidate.
     */
    public Fabric262ReloadPlan {
        if (generation < 0L) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
        Objects.requireNonNull(handles, "handles");
        LinkedHashMap<BlendModelKey, Fabric262PreparedModelHandle> ordered = new LinkedHashMap<>();
        handles.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(BlendModelKey::value)))
                .forEach(entry -> ordered.put(
                        Objects.requireNonNull(entry.getKey(), "handles key"),
                        Objects.requireNonNull(entry.getValue(), "handles value")));
        handles = Collections.unmodifiableMap(ordered);
        diagnostics = List.copyOf(new ArrayList<>(Objects.requireNonNull(diagnostics, "diagnostics")));
    }
}
