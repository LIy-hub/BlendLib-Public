package com.liy.blendlib.fabric.v262.resource;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.asset.AssetBytes;
import com.liy.blendlib.core.limits.BlendAssetLimits;
import com.liy.blendlib.fabric.v262.diagnostic.Fabric262Diagnostic;
import com.liy.blendlib.fabric.v262.diagnostic.Fabric262DiagnosticCode;
import com.liy.blendlib.fabric.v262.diagnostic.Fabric262PlatformException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * Fabric resource-manager implementation of the reload-only resource access contract.
 *
 * <p><strong>Platform target:</strong> Fabric on Minecraft 26.2. Minecraft {@link Identifier}
 * values are converted at this boundary and never stored in pure API/core objects. Every returned
 * payload is copied into immutable {@link AssetBytes} before core decoding begins.</p>
 */
public final class Fabric262MinecraftResourceAccess implements Fabric262ResourceAccess {
    private static final String DESCRIPTOR_DIRECTORY = "blend_models";
    private static final int MAX_DESCRIPTOR_BYTES = 1 * 1024 * 1024;

    private final ResourceManager resourceManager;

    /**
     * Creates a reload-bound adapter over a non-null Minecraft resource manager.
     *
     * @param resourceManager current Fabric/Minecraft resource manager
     */
    public Fabric262MinecraftResourceAccess(ResourceManager resourceManager) {
        this.resourceManager = Objects.requireNonNull(resourceManager, "resourceManager");
    }

    /**
     * Discovers strict descriptor files and converts them into semantic keys in stable order.
     *
     * @return immutable ascending semantic model-key list
     * @throws Fabric262PlatformException when a discovered path cannot represent a strict key
     */
    @Override
    public List<BlendModelKey> discoverModels() {
        Map<Identifier, ?> descriptors = resourceManager.listResources(
                DESCRIPTOR_DIRECTORY, path -> path.endsWith(".json"));
        return descriptors.keySet().stream()
                .map(Fabric262MinecraftResourceAccess::fromIdentifier)
                .map(BlendModelKey::fromDescriptorResourceId)
                .sorted(Comparator.comparing(BlendModelKey::value))
                .toList();
    }

    /**
     * Reads the exact descriptor resource for a semantic key.
     *
     * @param key strict semantic model key
     * @return immutable descriptor bytes
     */
    @Override
    public AssetBytes descriptor(BlendModelKey key) {
        return resolve(Objects.requireNonNull(key, "key").descriptorResourceId());
    }

    /**
     * Copies one bounded Fabric resource into a pure immutable byte payload.
     *
     * @param resourceId strict resource identity
     * @return immutable resource bytes
     * @throws Fabric262PlatformException when the resource is absent or cannot be read
     */
    @Override
    public AssetBytes resolve(BlendResourceId resourceId) {
        BlendResourceId checkedId = Objects.requireNonNull(resourceId, "resourceId");
        Identifier identifier = toIdentifier(checkedId);
        try {
            var resource = resourceManager.getResource(identifier).orElseThrow(() -> new IOException(
                    "Resource is absent: " + checkedId));
            try (InputStream input = resource.open()) {
                return new AssetBytes(checkedId, readBounded(input, checkedId, maximumBytes(checkedId)));
            }
        } catch (IOException exception) {
            throw new Fabric262PlatformException(Fabric262Diagnostic.error(
                    Fabric262DiagnosticCode.RESOURCE_LOAD_FAILURE,
                    "Unable to read " + checkedId + " during Fabric resource reload"), exception);
        }
    }

    private static int maximumBytes(BlendResourceId resourceId) {
        return resourceId.path().startsWith(DESCRIPTOR_DIRECTORY + "/") && resourceId.path().endsWith(".json")
                ? MAX_DESCRIPTOR_BYTES
                : BlendAssetLimits.DEFAULT.maxGlbBytes();
    }

    /**
     * Reads an untrusted selected resource without first allocating an unbounded byte array.
     *
     * <p>The limit is checked before every copy, including a non-conforming zero-byte read. This
     * keeps descriptor and referenced strict resource handling bounded at the Fabric boundary
     * before {@link AssetBytes} creates its defensive copy.</p>
     */
    private static byte[] readBounded(InputStream input, BlendResourceId resourceId, int maximumBytes)
            throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(resourceId, "resourceId");
        if (maximumBytes <= 0) {
            throw new IllegalArgumentException("maximumBytes must be positive");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximumBytes, 8 * 1024));
        byte[] buffer = new byte[8 * 1024];
        int totalBytes = 0;
        while (true) {
            int count = input.read(buffer);
            if (count < 0) {
                return output.toByteArray();
            }
            if (count == 0) {
                int singleByte = input.read();
                if (singleByte < 0) {
                    return output.toByteArray();
                }
                ensureCapacity(resourceId, totalBytes, 1, maximumBytes);
                output.write(singleByte);
                totalBytes++;
                continue;
            }
            ensureCapacity(resourceId, totalBytes, count, maximumBytes);
            output.write(buffer, 0, count);
            totalBytes += count;
        }
    }

    private static void ensureCapacity(
            BlendResourceId resourceId,
            int currentBytes,
            int nextBytes,
            int maximumBytes) {
        if (nextBytes > maximumBytes - currentBytes) {
            throw new Fabric262PlatformException(Fabric262Diagnostic.error(
                    Fabric262DiagnosticCode.RESOURCE_SIZE_LIMIT,
                    "Fabric 26.2 reload resource exceeds " + maximumBytes + " bytes: " + resourceId));
        }
    }

    /**
     * Converts a pure strict resource identity at the Fabric boundary.
     *
     * @param resourceId pure resource identity
     * @return Minecraft identifier used only in this adapter
     */
    public static Identifier toIdentifier(BlendResourceId resourceId) {
        BlendResourceId checkedId = Objects.requireNonNull(resourceId, "resourceId");
        return Identifier.fromNamespaceAndPath(checkedId.namespace(), checkedId.path());
    }

    /**
     * Converts a Fabric identifier at the Fabric boundary.
     *
     * @param identifier Minecraft identifier
     * @return pure canonical resource identity
     */
    public static BlendResourceId fromIdentifier(Identifier identifier) {
        Identifier checkedIdentifier = Objects.requireNonNull(identifier, "identifier");
        return BlendResourceId.of(checkedIdentifier.getNamespace(), checkedIdentifier.getPath());
    }
}
