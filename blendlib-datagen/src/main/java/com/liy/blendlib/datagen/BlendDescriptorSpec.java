package com.liy.blendlib.datagen;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Complete immutable authoring specification for one generated strict descriptor and sidecars.
 *
 * <p><strong>Stable boundary:</strong> the generated descriptor is strictly v1 and references only
 * {@code models3d/*.glb}; it never processes {@code .blend}, FBX, or OBJ. Variants/capabilities
 * are emitted as separate experimental sidecars rather than illegal descriptor fields.</p>
 *
 * @param modelKey semantic descriptor key
 * @param profile strict descriptor profile
 * @param mesh strict external {@code models3d/*.glb} resource
 * @param unitsPerBlock positive finite model-unit scale
 * @param materials named strict descriptor materials
 * @param animation optional strict descriptor animation graph
 * @param sockets strict descriptor sockets
 * @param variants experimental sidecar declarations
 * @param capabilities experimental sidecar declarations
 */
public record BlendDescriptorSpec(
        BlendModelKey modelKey,
        DatagenProfile profile,
        BlendResourceId mesh,
        double unitsPerBlock,
        Map<String, DatagenMaterial> materials,
        Optional<DatagenAnimationGraph> animation,
        List<DatagenSocket> sockets,
        List<DatagenVariant> variants,
        List<DatagenCapabilityDeclaration> capabilities) {
    /**
     * Validates a complete immutable strict descriptor authoring specification.
     */
    public BlendDescriptorSpec {
        modelKey = Objects.requireNonNull(modelKey, "modelKey");
        profile = Objects.requireNonNull(profile, "profile");
        mesh = Objects.requireNonNull(mesh, "mesh");
        if (!mesh.path().startsWith("models3d/") || !mesh.path().endsWith(".glb")) {
            throw new IllegalArgumentException("mesh must be a strict models3d/*.glb resource");
        }
        if (!Double.isFinite(unitsPerBlock) || unitsPerBlock <= 0.0) {
            throw new IllegalArgumentException("unitsPerBlock must be finite and positive");
        }
        materials = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(materials, "materials")));
        if (materials.isEmpty() || materials.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                || entry.getKey().isBlank() || entry.getValue() == null)) {
            throw new IllegalArgumentException("materials must contain non-blank slot names and non-null definitions");
        }
        DatagenLimits.requireAtMost("materials", materials.size(), DatagenLimits.MAX_MATERIAL_SLOTS);
        animation = Objects.requireNonNull(animation, "animation");
        sockets = List.copyOf(Objects.requireNonNull(sockets, "sockets"));
        variants = List.copyOf(Objects.requireNonNull(variants, "variants"));
        capabilities = List.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
        DatagenLimits.requireAtMost("sockets", sockets.size(), DatagenLimits.MAX_SOCKETS);
        DatagenLimits.requireAtMost("variants", variants.size(), DatagenLimits.MAX_VARIANTS);
        DatagenLimits.requireAtMost("capabilities", capabilities.size(), DatagenLimits.MAX_CAPABILITIES);
        ensureUniqueSockets(sockets);
        ensureUniqueVariants(variants);
        ensureUniqueCapabilities(capabilities);
    }

    private static void ensureUniqueSockets(List<DatagenSocket> sockets) {
        long distinct = sockets.stream().map(DatagenSocket::socketId).distinct().count();
        if (sockets.stream().anyMatch(Objects::isNull) || distinct != sockets.size()) {
            throw new IllegalArgumentException("sockets must be non-null and use unique identities");
        }
    }

    private static void ensureUniqueVariants(List<DatagenVariant> variants) {
        long distinct = variants.stream().map(DatagenVariant::variantId).distinct().count();
        if (variants.stream().anyMatch(Objects::isNull) || distinct != variants.size()) {
            throw new IllegalArgumentException("variants must be non-null and use unique identities");
        }
    }

    private static void ensureUniqueCapabilities(List<DatagenCapabilityDeclaration> capabilities) {
        long distinct = capabilities.stream().map(DatagenCapabilityDeclaration::capabilityId).distinct().count();
        if (capabilities.stream().anyMatch(Objects::isNull) || distinct != capabilities.size()) {
            throw new IllegalArgumentException("capabilities must be non-null and use unique identities");
        }
    }
}
