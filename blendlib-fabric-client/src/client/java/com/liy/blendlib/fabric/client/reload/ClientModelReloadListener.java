package com.liy.blendlib.fabric.client.reload;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.asset.AssetBytes;
import com.liy.blendlib.core.diagnostic.BlendAssetLoadException;
import com.liy.blendlib.core.diagnostic.BlendDiagnostic;
import com.liy.blendlib.core.diagnostic.BlendDiagnosticCodes;
import com.liy.blendlib.core.loader.ModelAssetLoader;
import com.liy.blendlib.core.limits.BlendAssetLimits;
import com.liy.blendlib.core.model.ModelAsset;
import com.liy.blendlib.fabric.BlendFabricResourceIds;
import com.liy.blendlib.fabric.client.render.ModelRenderHandle;
import com.liy.blendlib.fabric.client.render.SkinnedRenderHandle;
import com.liy.blendlib.fabric.client.render.StaticRigidRenderHandle;
import com.liy.blendlib.fabric.client.render.UnsupportedRenderMaterialException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongConsumer;
import net.fabricmc.fabric.api.resource.v1.reloader.SimpleReloadListener;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * Client-only strict model reload listener.
 *
 * <p>Prepare resolves only final {@link ResourceManager} resources, reads descriptor/GLB bytes, and validates core
 * assets. Apply builds backend-ready handles for that whole prepared result before atomically publishing it.</p>
 */
public final class ClientModelReloadListener extends SimpleReloadListener<PreparedModelGeneration> {
    private static final String DESCRIPTOR_DIRECTORY = "blend_models";
    private static final String DESCRIPTOR_SUFFIX = ".json";
    static final int MAX_DESCRIPTOR_BYTES = 1 * 1024 * 1024;
    private static final LongConsumer NO_ACTIVE_GENERATION_LISTENER = ignored -> { };

    private final ClientModelRegistry registry;
    private final ModelAssetLoader loader;
    private final LongConsumer activeGenerationListener;
    private final ReloadDiagnosticsReporter diagnosticsReporter;

    public ClientModelReloadListener(ClientModelRegistry registry) {
        this(registry, new ModelAssetLoader(), NO_ACTIVE_GENERATION_LISTENER, ReloadDiagnosticsReporter.production());
    }

    /**
     * Creates a client reload listener that retires dependent client-only state after publication.
     *
     * <p>The listener receives the generation returned by {@link ClientModelRegistry#publish(ModelRegistryGeneration)},
     * rather than the prepared candidate. This keeps a late stale prepare result from retiring state for the real
     * active generation.</p>
     */
    public ClientModelReloadListener(ClientModelRegistry registry, LongConsumer activeGenerationListener) {
        this(registry, new ModelAssetLoader(), activeGenerationListener, ReloadDiagnosticsReporter.production());
    }

    ClientModelReloadListener(ClientModelRegistry registry, ModelAssetLoader loader) {
        this(registry, loader, NO_ACTIVE_GENERATION_LISTENER, ReloadDiagnosticsReporter.production());
    }

    ClientModelReloadListener(
            ClientModelRegistry registry, ModelAssetLoader loader, LongConsumer activeGenerationListener) {
        this(registry, loader, activeGenerationListener, ReloadDiagnosticsReporter.production());
    }

    ClientModelReloadListener(
            ClientModelRegistry registry,
            LongConsumer activeGenerationListener,
            ReloadDiagnosticsReporter diagnosticsReporter) {
        this(registry, new ModelAssetLoader(), activeGenerationListener, diagnosticsReporter);
    }

    ClientModelReloadListener(
            ClientModelRegistry registry,
            ModelAssetLoader loader,
            LongConsumer activeGenerationListener,
            ReloadDiagnosticsReporter diagnosticsReporter) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.loader = Objects.requireNonNull(loader, "loader");
        this.activeGenerationListener = Objects.requireNonNull(activeGenerationListener, "activeGenerationListener");
        this.diagnosticsReporter = Objects.requireNonNull(diagnosticsReporter, "diagnosticsReporter");
    }

    @Override
    protected PreparedModelGeneration prepare(PreparableReloadListener.SharedState state) {
        ResourceManager resourceManager = Objects.requireNonNull(state, "state").resourceManager();
        long generationId = registry.reserveNextGenerationId();
        Map<BlendModelKey, ModelAsset> loadedAssets = new LinkedHashMap<>();
        Map<BlendModelKey, BlendDiagnostic> primaryDiagnostics = new LinkedHashMap<>();
        List<BlendDiagnostic> globalDiagnostics = new ArrayList<>();

        List<Map.Entry<Identifier, Resource>> descriptors = new ArrayList<>(resourceManager
                .listResources(DESCRIPTOR_DIRECTORY, ClientModelReloadListener::isDescriptorResource)
                .entrySet());
        descriptors.sort(Comparator.comparing(entry -> entry.getKey().toString()));

        for (Map.Entry<Identifier, Resource> descriptorEntry : descriptors) {
            loadDescriptor(
                    resourceManager,
                    generationId,
                    descriptorEntry.getKey(),
                    descriptorEntry.getValue(),
                    loadedAssets,
                    primaryDiagnostics,
                    globalDiagnostics);
        }

        return new PreparedModelGeneration(generationId, loadedAssets, primaryDiagnostics, globalDiagnostics);
    }

    @Override
    protected void apply(PreparedModelGeneration prepared, PreparableReloadListener.SharedState state) {
        ModelRegistryGeneration candidateGeneration = createPublishedGeneration(Objects.requireNonNull(prepared, "prepared"));
        ModelRegistryGeneration activeGeneration = registry.publish(candidateGeneration);
        diagnosticsReporter.report(candidateGeneration, activeGeneration);
        activeGenerationListener.accept(activeGeneration.generationId());
    }

    private void loadDescriptor(
            ResourceManager resourceManager,
            long generationId,
            Identifier descriptorIdentifier,
            Resource descriptorResource,
            Map<BlendModelKey, ModelAsset> loadedAssets,
            Map<BlendModelKey, BlendDiagnostic> primaryDiagnostics,
            List<BlendDiagnostic> globalDiagnostics) {
        BlendResourceId descriptorId;
        BlendModelKey modelKey;
        try {
            descriptorId = BlendFabricResourceIds.fromIdentifier(descriptorIdentifier);
            modelKey = BlendModelKey.fromDescriptorResourceId(descriptorId);
        } catch (RuntimeException exception) {
            BlendResourceId resourceId = safeResourceId(descriptorIdentifier);
            globalDiagnostics.add(BlendDiagnostic.error(
                            BlendDiagnosticCodes.DESC_002,
                            null,
                            resourceId,
                            "/",
                            "Descriptor resource path cannot be mapped to a BlendModelKey")
                    .withCause(exception));
            return;
        }

        try {
            AssetBytes descriptorBytes = new AssetBytes(
                    descriptorId,
                    readBoundedBytes(
                            descriptorResource,
                            modelKey.resourceId(),
                            descriptorId,
                            MAX_DESCRIPTOR_BYTES,
                            "",
                            "Descriptor"));
            ModelAsset asset = loader.load(
                    modelKey.resourceId(),
                    generationId,
                    descriptorBytes,
                    resourceId -> readRequiredResource(resourceManager, modelKey, resourceId));
            verifyExternalTextures(resourceManager, modelKey, asset);
            loadedAssets.putIfAbsent(modelKey, asset);
        } catch (BlendAssetLoadException exception) {
            recordPrimaryDiagnostic(modelKey, exception.diagnostic(), loadedAssets, primaryDiagnostics);
        } catch (RuntimeException exception) {
            BlendDiagnostic diagnostic = BlendDiagnostic.error(
                            BlendDiagnosticCodes.DESC_002,
                            modelKey.resourceId(),
                            descriptorId,
                            "/",
                            "Unable to load descriptor and strict GLB resources")
                    .withCause(exception);
            recordPrimaryDiagnostic(modelKey, diagnostic, loadedAssets, primaryDiagnostics);
        }
    }

    /** Builds immutable render handles before a generation becomes visible to lookup or submit code. */
    static ModelRegistryGeneration createPublishedGeneration(PreparedModelGeneration prepared) {
        Objects.requireNonNull(prepared, "prepared");
        long generationId = prepared.generationId();
        Map<BlendModelKey, ModelHandle> handles = new LinkedHashMap<>();
        Map<BlendModelKey, BlendDiagnostic> primaryDiagnostics = new LinkedHashMap<>(prepared.primaryDiagnostics());

        primaryDiagnostics.forEach((key, diagnostic) -> handles.put(
                key,
                MissingModelHandle.failed(key, generationId, diagnostic)));

        for (Map.Entry<BlendModelKey, ModelAsset> entry : prepared.loadedAssets().entrySet()) {
            BlendModelKey modelKey = entry.getKey();
            ModelAsset asset = entry.getValue();
            try {
                handles.put(modelKey, new LoadedModelHandle(modelKey, asset, prepareRenderHandle(modelKey, asset)));
            } catch (UnsupportedRenderMaterialException exception) {
                recordBackendMissing(
                        modelKey,
                        generationId,
                        unsupportedMaterialDiagnostic(modelKey, asset, exception),
                        handles,
                        primaryDiagnostics);
            } catch (RuntimeException exception) {
                        BlendDiagnostic diagnostic = BlendDiagnostic.error(
                                BlendDiagnosticCodes.DESC_002,
                                modelKey.resourceId(),
                                asset.descriptorId(),
                                "/render",
                                "Unable to prepare the backend render handle")
                        .withCause(exception);
                recordBackendMissing(modelKey, generationId, diagnostic, handles, primaryDiagnostics);
            }
        }
        return new ModelRegistryGeneration(generationId, handles, primaryDiagnostics, prepared.globalDiagnostics());
    }

    /** Selects a complete immutable adapter handle at reload time before a generation becomes visible. */
    private static ModelRenderHandle prepareRenderHandle(BlendModelKey modelKey, ModelAsset asset) {
        return switch (asset.profile()) {
            case RIGID_V1 -> StaticRigidRenderHandle.prepare(modelKey, asset);
            case SKINNED_V1 -> SkinnedRenderHandle.prepare(modelKey, asset);
        };
    }

    private static BlendDiagnostic unsupportedMaterialDiagnostic(
            BlendModelKey modelKey, ModelAsset asset, UnsupportedRenderMaterialException exception) {
        return BlendDiagnostic.error(
                        BlendDiagnosticCodes.MAT_004,
                        modelKey.resourceId(),
                        asset.descriptorId(),
                        "/materials/" + escapeJsonPointerSegment(exception.materialSlot()) + "/"
                                + exception.reason().descriptorField(),
                        exception.reason().name() + ": " + exception.getMessage())
                .withCause(exception);
    }

    private static String escapeJsonPointerSegment(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private static boolean isDescriptorResource(Identifier identifier) {
        String path = identifier.getPath();
        return path.startsWith(DESCRIPTOR_DIRECTORY + "/") && path.endsWith(DESCRIPTOR_SUFFIX);
    }

    private static AssetBytes readRequiredResource(
            ResourceManager resourceManager, BlendModelKey modelKey, BlendResourceId resourceId) {
        Identifier identifier = BlendFabricResourceIds.toIdentifier(resourceId);
        Resource resource = resourceManager
                .getResource(identifier)
                .orElseThrow(() -> new BlendAssetLoadException(BlendDiagnostic.error(
                        BlendDiagnosticCodes.DESC_002,
                        modelKey.resourceId(),
                        resourceId,
                        "/mesh",
                        "Missing required resource")));
        return new AssetBytes(resourceId, readBoundedBytes(
                resource,
                modelKey.resourceId(),
                resourceId,
                BlendAssetLimits.DEFAULT.maxGlbBytes(),
                "/mesh",
                "GLB"));
    }

    /**
     * Reads at most {@code maximumBytes} from an untrusted selected resource.
     *
     * <p>The limit is checked before each write so a resource pack cannot make reload allocate a byte array based on
     * an unbounded stream. Callers receive a stable diagnostic rather than an incidental allocation failure.</p>
     */
    static byte[] readBoundedBytes(
            Resource resource,
            BlendResourceId modelKey,
            BlendResourceId resourceId,
            int maximumBytes,
            String location,
            String resourceKind) {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(modelKey, "modelKey");
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(resourceKind, "resourceKind");
        if (maximumBytes <= 0) {
            throw new IllegalArgumentException("maximumBytes must be positive");
        }
        try (InputStream stream = resource.open()) {
            List<byte[]> chunks = new ArrayList<>();
            int totalBytes = 0;
            byte[] buffer = new byte[8 * 1024];
            while (true) {
                int count = stream.read(buffer);
                if (count < 0) {
                    return flattenChunks(chunks, totalBytes);
                }
                if (count == 0) {
                    int singleByte = stream.read();
                    if (singleByte < 0) {
                        return flattenChunks(chunks, totalBytes);
                    }
                    totalBytes = appendBoundedChunk(
                            chunks, new byte[] {(byte) singleByte}, 1, totalBytes, maximumBytes, modelKey, resourceId, location, resourceKind);
                    continue;
                }
                totalBytes = appendBoundedChunk(
                        chunks, buffer, count, totalBytes, maximumBytes, modelKey, resourceId, location, resourceKind);
            }
        } catch (IOException exception) {
            throw new BlendAssetLoadException(BlendDiagnostic.error(
                    BlendDiagnosticCodes.DESC_002,
                    modelKey,
                    resourceId,
                    location,
                    "Unable to read selected " + resourceKind + " resource"), exception);
        }
    }

    private static int appendBoundedChunk(
            List<byte[]> chunks,
            byte[] source,
            int count,
            int totalBytes,
            int maximumBytes,
            BlendResourceId modelKey,
            BlendResourceId resourceId,
            String location,
            String resourceKind) {
        if (count > maximumBytes - totalBytes) {
            throw byteLimitExceeded(modelKey, resourceId, location, resourceKind);
        }
        byte[] chunk = new byte[count];
        System.arraycopy(source, 0, chunk, 0, count);
        chunks.add(chunk);
        return totalBytes + count;
    }

    private static byte[] flattenChunks(List<byte[]> chunks, int totalBytes) {
        byte[] result = new byte[totalBytes];
        int destination = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, result, destination, chunk.length);
            destination += chunk.length;
        }
        return result;
    }

    private static BlendAssetLoadException byteLimitExceeded(
            BlendResourceId modelKey, BlendResourceId resourceId, String location, String resourceKind) {
        return new BlendAssetLoadException(BlendDiagnostic.error(
                BlendDiagnosticCodes.LIMIT_001,
                modelKey,
                resourceId,
                location,
                resourceKind + " byte limit exceeded"));
    }

    private static void verifyExternalTextures(ResourceManager resourceManager, BlendModelKey modelKey, ModelAsset asset) {
        asset.materials().forEach((slot, material) -> {
            Identifier textureIdentifier = BlendFabricResourceIds.toIdentifier(material.baseColor());
            if (resourceManager.getResource(textureIdentifier).isEmpty()) {
                throw new BlendAssetLoadException(BlendDiagnostic.error(
                        BlendDiagnosticCodes.DESC_002,
                        modelKey.resourceId(),
                        material.baseColor(),
                        "/materials/" + slot + "/base_color",
                        "Missing external texture resource"));
            }
        });
    }

    private static void recordPrimaryDiagnostic(
            BlendModelKey modelKey,
            BlendDiagnostic diagnostic,
            Map<BlendModelKey, ModelAsset> loadedAssets,
            Map<BlendModelKey, BlendDiagnostic> primaryDiagnostics) {
        if (loadedAssets.containsKey(modelKey)) {
            return;
        }
        primaryDiagnostics.putIfAbsent(modelKey, Objects.requireNonNull(diagnostic, "diagnostic"));
    }

    private static void recordBackendMissing(
            BlendModelKey modelKey,
            long generationId,
            BlendDiagnostic diagnostic,
            Map<BlendModelKey, ModelHandle> handles,
            Map<BlendModelKey, BlendDiagnostic> primaryDiagnostics) {
        BlendDiagnostic primary = primaryDiagnostics.putIfAbsent(modelKey, Objects.requireNonNull(diagnostic, "diagnostic"));
        handles.put(modelKey, MissingModelHandle.failed(modelKey, generationId, primary == null ? diagnostic : primary));
    }

    private static BlendResourceId safeResourceId(Identifier identifier) {
        try {
            return BlendFabricResourceIds.fromIdentifier(identifier);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
