package com.liy.blendlib.neoforge.v262.bridge;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.core.model.ModelAsset;
import com.liy.blendlib.neoforge.v262.NeoForge262Diagnostic;
import com.liy.blendlib.neoforge.v262.NeoForge262DiagnosticCode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Immutable strict-resource generation issued by exactly one NeoForge 26.2 bridge instance.
 *
 * <p><strong>WAITING boundary:</strong> this remains pure lifecycle data, not a native render
 * handle. Its constructor and issuer token are deliberately private: an external caller cannot
 * fabricate a future generation (including {@link Long#MAX_VALUE}) to poison a bridge's monotonic
 * apply sequence. Only the owning bridge can issue and claim a generation for publication.</p>
 */
public final class NeoForge262PreparedGeneration {
    private final Object issuerToken;
    private final long generation;
    private final Map<BlendModelKey, ModelAsset> assets;
    private final List<NeoForge262Diagnostic> diagnostics;
    private final AtomicBoolean applied = new AtomicBoolean();

    private NeoForge262PreparedGeneration(
            Object issuerToken,
            long generation,
            Map<BlendModelKey, ModelAsset> assets,
            List<NeoForge262Diagnostic> diagnostics) {
        this.issuerToken = Objects.requireNonNull(issuerToken, "issuerToken");
        if (generation < 0L || generation == Long.MAX_VALUE) {
            throw new IllegalArgumentException("generation must be non-negative and below Long.MAX_VALUE");
        }
        this.generation = generation;
        Objects.requireNonNull(assets, "assets");
        LinkedHashMap<BlendModelKey, ModelAsset> ordered = new LinkedHashMap<>();
        assets.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(BlendModelKey::value)))
                .forEach(entry -> {
                    BlendModelKey key = Objects.requireNonNull(entry.getKey(), "assets key");
                    ModelAsset asset = Objects.requireNonNull(entry.getValue(), "assets value");
                    if (!key.resourceId().equals(asset.modelKey()) || asset.generation() != generation) {
                        throw new IllegalArgumentException(
                                "Every NeoForge prepared asset must match its key and generation " + generation);
                    }
                    ordered.put(key, asset);
                });
        this.assets = Collections.unmodifiableMap(ordered);
        this.diagnostics = List.copyOf(new ArrayList<>(Objects.requireNonNull(diagnostics, "diagnostics")));
    }

    static NeoForge262PreparedGeneration initial(Object issuerToken) {
        return new NeoForge262PreparedGeneration(issuerToken, 0L, Map.of(), List.of());
    }

    static NeoForge262PreparedGeneration issued(
            Object issuerToken,
            long generation,
            Map<BlendModelKey, ModelAsset> assets,
            List<NeoForge262Diagnostic> diagnostics) {
        if (generation <= 0L || generation == Long.MAX_VALUE) {
            throw new IllegalArgumentException("issued generation must be in [1, Long.MAX_VALUE)");
        }
        return new NeoForge262PreparedGeneration(issuerToken, generation, assets, diagnostics);
    }

    /** Returns the non-negative bridge generation. */
    public long generation() {
        return generation;
    }

    /** Returns immutable strict ready assets keyed by their semantic model identity. */
    public Map<BlendModelKey, ModelAsset> assets() {
        return assets;
    }

    /** Returns deterministic immutable preparation diagnostics. */
    public List<NeoForge262Diagnostic> diagnostics() {
        return diagnostics;
    }

    /**
     * Selects an immutable ready asset or an explicit no-render fallback.
     *
     * @param key strict semantic model key
     * @return ready asset or diagnostic-backed fallback selection
     */
    public NeoForge262ModelSelection select(BlendModelKey key) {
        BlendModelKey checked = Objects.requireNonNull(key, "key");
        ModelAsset asset = assets.get(checked);
        if (asset != null) {
            return new NeoForge262ModelSelection(checked, generation, java.util.Optional.of(asset), java.util.Optional.empty());
        }
        return new NeoForge262ModelSelection(checked, generation, java.util.Optional.empty(), java.util.Optional.of(
                NeoForge262Diagnostic.model(
                        NeoForge262DiagnosticCode.MODEL_NOT_PUBLISHED,
                        checked,
                        "No strict asset is published for this key in NeoForge bridge generation " + generation)));
    }

    boolean isIssuedBy(Object expectedIssuerToken) {
        return issuerToken == Objects.requireNonNull(expectedIssuerToken, "expectedIssuerToken");
    }

    boolean assetsMatchGeneration() {
        return assets.entrySet().stream().allMatch(entry -> entry.getKey().resourceId().equals(entry.getValue().modelKey())
                && entry.getValue().generation() == generation);
    }

    boolean claimForApply(Object expectedIssuerToken) {
        return isIssuedBy(expectedIssuerToken) && applied.compareAndSet(false, true);
    }
}
