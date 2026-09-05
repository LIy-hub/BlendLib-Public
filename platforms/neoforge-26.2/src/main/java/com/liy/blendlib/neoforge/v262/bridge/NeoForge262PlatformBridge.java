package com.liy.blendlib.neoforge.v262.bridge;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.api.HostRegistrationSpec;
import com.liy.blendlib.core.loader.ModelAssetLoader;
import com.liy.blendlib.neoforge.v262.NeoForge262BridgeException;
import com.liy.blendlib.neoforge.v262.NeoForge262Diagnostic;
import com.liy.blendlib.neoforge.v262.NeoForge262DiagnosticCode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pure lifecycle bridge for a future independently versioned NeoForge 26.2 adapter.
 *
 * <p><strong>WAITING boundary:</strong> this class is intentionally free of Fabric, NeoForge, and
 * Minecraft imports. It fully implements strict descriptor/GLB prepare, immutable apply, semantic
 * host translation, diagnostics, and no-render fallback selection. A future verified binder owns
 * only source adaptation, event registration, native host registry installation, and final client
 * submission.</p>
 */
public final class NeoForge262PlatformBridge {
    private final Object issuerToken = new Object();
    private final ModelAssetLoader loader;
    private final AtomicLong nextGeneration = new AtomicLong();
    private final AtomicReference<NeoForge262PreparedGeneration> active = new AtomicReference<>(
            NeoForge262PreparedGeneration.initial(issuerToken));

    /**
     * Creates a bridge using the standard strict pure-Java model loader.
     */
    public NeoForge262PlatformBridge() {
        this(new ModelAssetLoader());
    }

    /**
     * Creates a bridge with an explicit pure-Java strict model loader.
     *
     * @param loader loader that has no platform dependency
     */
    public NeoForge262PlatformBridge(ModelAssetLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    /**
     * Performs strict descriptor/GLB preparation through a future platform-owned source.
     *
     * <p>Each failed asset is excluded from the ready map and recorded as a diagnostic-backed
     * no-render fallback. This is fail-closed for a later native renderer: it cannot accidentally
     * submit a partial model. Java {@link Error} values remain unmodified and are not converted.</p>
     *
     * @param source authorized, reload-scoped pure resource source
     * @return immutable unpublished bridge generation
     */
    public NeoForge262PreparedGeneration prepare(NeoForge262ResourceSource source) {
        NeoForge262ResourceSource checkedSource = Objects.requireNonNull(source, "source");
        long generation = allocateGeneration();
        LinkedHashMap<BlendModelKey, com.liy.blendlib.core.model.ModelAsset> assets = new LinkedHashMap<>();
        List<NeoForge262Diagnostic> diagnostics = new ArrayList<>();
        List<BlendModelKey> discovered = new ArrayList<>(checkedSource.discoverModels());
        discovered.forEach(key -> Objects.requireNonNull(key, "source returned null model key"));
        discovered.sort(Comparator.comparing(BlendModelKey::value));
        BlendModelKey previous = null;
        for (BlendModelKey key : discovered) {
            BlendModelKey checkedKey = Objects.requireNonNull(key, "source returned null model key");
            if (checkedKey.equals(previous)) {
                diagnostics.add(NeoForge262Diagnostic.model(
                        NeoForge262DiagnosticCode.RESOURCE_PREPARATION_FAILURE,
                        checkedKey,
                        "NeoForge bridge resource source returned the same model key more than once"));
                continue;
            }
            previous = checkedKey;
            try {
                assets.put(checkedKey, loader.load(
                        checkedKey.resourceId(), generation, checkedSource.descriptor(checkedKey), checkedSource));
            } catch (RuntimeException exception) {
                diagnostics.add(NeoForge262Diagnostic.model(
                        NeoForge262DiagnosticCode.RESOURCE_PREPARATION_FAILURE,
                        checkedKey,
                        "Strict bridge preparation failed: " + boundedCause(exception)));
            }
        }
        return NeoForge262PreparedGeneration.issued(issuerToken, generation, assets, diagnostics);
    }

    /**
     * Atomically publishes a complete immutable bridge generation.
     *
     * @param generation prepared immutable bridge generation
     * @return the same published immutable generation
     */
    public synchronized NeoForge262PreparedGeneration apply(NeoForge262PreparedGeneration generation) {
        NeoForge262PreparedGeneration checked = Objects.requireNonNull(generation, "generation");
        if (!checked.isIssuedBy(issuerToken)) {
            throw new NeoForge262BridgeException(NeoForge262Diagnostic.global(
                    NeoForge262DiagnosticCode.FOREIGN_GENERATION,
                    "NeoForge bridge rejects a prepared generation issued by another bridge"));
        }
        if (checked.generation() <= 0L || checked.generation() == Long.MAX_VALUE) {
            throw new NeoForge262BridgeException(NeoForge262Diagnostic.global(
                    NeoForge262DiagnosticCode.FOREIGN_GENERATION,
                    "NeoForge bridge rejects an invalid or reserved prepared generation number"));
        }
        if (!checked.assetsMatchGeneration()) {
            throw new NeoForge262BridgeException(NeoForge262Diagnostic.global(
                    NeoForge262DiagnosticCode.ASSET_GENERATION_MISMATCH,
                    "Every asset in a NeoForge prepared generation must retain the enclosing generation"));
        }
        NeoForge262PreparedGeneration previous = active.get();
        if (checked.generation() <= previous.generation()) {
            throw new NeoForge262BridgeException(NeoForge262Diagnostic.global(
                    NeoForge262DiagnosticCode.STALE_GENERATION,
                    "NeoForge bridge generation " + checked.generation()
                            + " is not newer than active generation " + previous.generation()));
        }
        if (!checked.claimForApply(issuerToken)) {
            throw new NeoForge262BridgeException(NeoForge262Diagnostic.global(
                    NeoForge262DiagnosticCode.GENERATION_ALREADY_APPLIED,
                    "NeoForge bridge generations are single-use and cannot be applied twice"));
        }
        active.set(checked);
        return checked;
    }

    /**
     * Returns an explicit ready-or-fallback selection from the currently published generation.
     *
     * @param modelKey strict semantic model key
     * @return immutable ready asset or diagnostic-backed no-render fallback
     */
    public NeoForge262ModelSelection select(BlendModelKey modelKey) {
        return active.get().select(Objects.requireNonNull(modelKey, "modelKey"));
    }

    /**
     * Returns the current immutable bridge generation for a later verified lifecycle owner.
     *
     * @return active pure bridge generation
     */
    public NeoForge262PreparedGeneration activeGeneration() {
        return active.get();
    }

    /**
     * Translates one stable registration through an explicitly supplied future host-identity resolver.
     *
     * <p>The native token stays opaque. This protects API/core from NeoForge types and prevents
     * accidental reflection-based guessing while the official 26.2 binding is WAITING.</p>
     *
     * @param specification stable semantic host registration
     * @param resolver future verified native-host identity resolver
     * @param <H> opaque consumer/native host token type
     * @return immutable pure host-binding result
     * @throws NeoForge262BridgeException when the supplied resolver cannot produce a canonical identity
     */
    public <H> NeoForge262HostBinding translateHost(
            HostRegistrationSpec<H> specification,
            NeoForge262HostIdentityResolver resolver) {
        HostRegistrationSpec<H> checked = Objects.requireNonNull(specification, "specification");
        NeoForge262HostIdentityResolver checkedResolver = Objects.requireNonNull(resolver, "resolver");
        BlendResourceId hostId;
        try {
            hostId = Objects.requireNonNull(
                    checkedResolver.resolve(checked.hostKind(), checked.host()),
                    "resolver returned null");
        } catch (RuntimeException exception) {
            throw new NeoForge262BridgeException(NeoForge262Diagnostic.global(
                    NeoForge262DiagnosticCode.HOST_IDENTITY_FAILURE,
                    "NeoForge bridge host identity resolution failed: " + boundedCause(exception)), exception);
        }
        return new NeoForge262HostBinding(checked.hostKind(), hostId, checked.model());
    }

    private static String boundedCause(RuntimeException exception) {
        String message = exception.getMessage();
        String value = message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
        return value.length() <= 384 ? value : value.substring(0, 381) + "...";
    }

    private long allocateGeneration() {
        while (true) {
            long current = nextGeneration.get();
            if (current >= Long.MAX_VALUE - 1L) {
                throw new NeoForge262BridgeException(NeoForge262Diagnostic.global(
                        NeoForge262DiagnosticCode.GENERATION_EXHAUSTED,
                        "NeoForge bridge cannot issue Long.MAX_VALUE as a generation sentinel"));
            }
            long candidate = current + 1L;
            if (nextGeneration.compareAndSet(current, candidate)) {
                return candidate;
            }
        }
    }
}
