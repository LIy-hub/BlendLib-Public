package com.liy.blendlib.fabric.client.reload;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.core.animation.rules.LocomotionRules;
import com.liy.blendlib.core.diagnostic.BlendDiagnostic;
import com.liy.blendlib.core.model.ModelAsset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable resource-I/O result produced by the reload prepare phase.
 *
 * <p>This DTO contains core assets, frozen named-skin definitions, optional locomotion rules and diagnostics only.
 * Backend render handles are created once in the apply phase, before a complete {@link ModelRegistryGeneration}
 * is atomically published.</p>
 */
public final class PreparedModelGeneration {
    private final Map<BlendModelKey, PreparedNamedSkins> namedSkins;
    private final Map<BlendModelKey, LocomotionRules> locomotionRules;
    private final long generationId;
    private final Map<BlendModelKey, ModelAsset> loadedAssets;
    private final Map<BlendModelKey, BlendDiagnostic> primaryDiagnostics;
    private final List<BlendDiagnostic> globalDiagnostics;

    public PreparedModelGeneration(
            long generationId,
            Map<BlendModelKey, ModelAsset> loadedAssets,
            Map<BlendModelKey, BlendDiagnostic> primaryDiagnostics,
            List<BlendDiagnostic> globalDiagnostics) {
        this(generationId, loadedAssets, primaryDiagnostics, globalDiagnostics, Map.of(), Map.of());
    }

    /** Creates a prepared generation with immutable optional rules for successfully loaded model assets. */
    public PreparedModelGeneration(long generationId, Map<BlendModelKey, ModelAsset> loadedAssets,
            Map<BlendModelKey, BlendDiagnostic> primaryDiagnostics,
            Map<BlendModelKey, LocomotionRules> locomotionRules, List<BlendDiagnostic> globalDiagnostics) {
        this(generationId, loadedAssets, primaryDiagnostics, globalDiagnostics, Map.of(), locomotionRules);
    }

    PreparedModelGeneration(long generationId, Map<BlendModelKey, ModelAsset> loadedAssets,
            Map<BlendModelKey, BlendDiagnostic> primaryDiagnostics, List<BlendDiagnostic> globalDiagnostics,
            Map<BlendModelKey, PreparedNamedSkins> namedSkins) {
        this(generationId, loadedAssets, primaryDiagnostics, globalDiagnostics, namedSkins, Map.of());
    }

    PreparedModelGeneration(long generationId, Map<BlendModelKey, ModelAsset> loadedAssets,
            Map<BlendModelKey, BlendDiagnostic> primaryDiagnostics, List<BlendDiagnostic> globalDiagnostics,
            Map<BlendModelKey, PreparedNamedSkins> namedSkins, Map<BlendModelKey, LocomotionRules> locomotionRules) {
        this.namedSkins = Map.copyOf(namedSkins);
        if (generationId < 0L) {
            throw new IllegalArgumentException("generationId must be non-negative");
        }
        this.generationId = generationId;
        this.loadedAssets = immutableAssets(loadedAssets, generationId);
        this.locomotionRules = immutableRules(locomotionRules, this.loadedAssets);
        this.primaryDiagnostics = immutableDiagnostics(primaryDiagnostics, this.loadedAssets);
        this.globalDiagnostics = List.copyOf(Objects.requireNonNull(globalDiagnostics, "globalDiagnostics"));
    }

    PreparedNamedSkins namedSkins(BlendModelKey model) {
        return namedSkins.getOrDefault(model, PreparedNamedSkins.EMPTY);
    }

    /** Immutable, prepare-time rules; absence also represents an invalid optional sidecar. */
    public Optional<LocomotionRules> locomotionRules(BlendModelKey model) {
        return Optional.ofNullable(locomotionRules.get(Objects.requireNonNull(model, "model")));
    }

    public long generationId() {
        return generationId;
    }

    public Map<BlendModelKey, ModelAsset> loadedAssets() {
        return loadedAssets;
    }

    public Map<BlendModelKey, BlendDiagnostic> primaryDiagnostics() {
        return primaryDiagnostics;
    }

    public List<BlendDiagnostic> globalDiagnostics() {
        return globalDiagnostics;
    }

    private static Map<BlendModelKey, LocomotionRules> immutableRules(
            Map<BlendModelKey, LocomotionRules> input, Map<BlendModelKey, ModelAsset> assets) {
        Objects.requireNonNull(input, "locomotionRules");
        Map<BlendModelKey, LocomotionRules> copy = new LinkedHashMap<>();
        input.forEach((key, rules) -> {
            BlendModelKey checkedKey = Objects.requireNonNull(key, "locomotion rules key");
            if (!assets.containsKey(checkedKey)) {
                throw new IllegalArgumentException("Locomotion rules must belong to a loaded asset in this generation");
            }
            copy.put(checkedKey, Objects.requireNonNull(rules, "locomotion rules"));
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Map<BlendModelKey, ModelAsset> immutableAssets(Map<BlendModelKey, ModelAsset> input, long generationId) {
        Objects.requireNonNull(input, "loadedAssets");
        Map<BlendModelKey, ModelAsset> copy = new LinkedHashMap<>();
        input.forEach((key, asset) -> {
            BlendModelKey checkedKey = Objects.requireNonNull(key, "asset key");
            ModelAsset checkedAsset = Objects.requireNonNull(asset, "asset");
            if (!checkedKey.resourceId().equals(checkedAsset.modelKey()) || checkedAsset.generation() != generationId) {
                throw new IllegalArgumentException("Every loaded asset must belong to this generation and model key");
            }
            copy.put(checkedKey, checkedAsset);
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Map<BlendModelKey, BlendDiagnostic> immutableDiagnostics(
            Map<BlendModelKey, BlendDiagnostic> input, Map<BlendModelKey, ModelAsset> assets) {
        Objects.requireNonNull(input, "primaryDiagnostics");
        Map<BlendModelKey, BlendDiagnostic> copy = new LinkedHashMap<>();
        input.forEach((key, diagnostic) -> {
            BlendModelKey checkedKey = Objects.requireNonNull(key, "diagnostic key");
            if (assets.containsKey(checkedKey)) {
                throw new IllegalArgumentException("A prepared key cannot have both a loaded asset and a primary diagnostic");
            }
            copy.put(checkedKey, Objects.requireNonNull(diagnostic, "diagnostic"));
        });
        return Collections.unmodifiableMap(copy);
    }
}
