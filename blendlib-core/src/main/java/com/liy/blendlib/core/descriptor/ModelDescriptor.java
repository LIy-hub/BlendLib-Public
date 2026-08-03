package com.liy.blendlib.core.descriptor;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.model.ModelProfile;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable strict v1 descriptor decoded before any GLB data is consumed. */
public final class ModelDescriptor {
    private final BlendResourceId descriptorId;
    private final ModelProfile profile;
    private final BlendResourceId meshId;
    private final double unitsPerBlock;
    private final Map<String, MaterialDefinition> materials;
    private final AnimationDefinition animation;
    private final Map<BlendResourceId, String> sockets;
    private final List<String> extensionsUsed;

    public ModelDescriptor(
            BlendResourceId descriptorId,
            ModelProfile profile,
            BlendResourceId meshId,
            double unitsPerBlock,
            Map<String, MaterialDefinition> materials,
            AnimationDefinition animation,
            Map<BlendResourceId, String> sockets,
            List<String> extensionsUsed) {
        this.descriptorId = Objects.requireNonNull(descriptorId, "descriptorId");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.meshId = Objects.requireNonNull(meshId, "meshId");
        if (!Double.isFinite(unitsPerBlock) || unitsPerBlock <= 0.0) {
            throw new IllegalArgumentException("unitsPerBlock must be finite and positive");
        }
        Objects.requireNonNull(materials, "materials");
        if (materials.isEmpty()) {
            throw new IllegalArgumentException("A descriptor must define at least one material slot");
        }
        this.unitsPerBlock = unitsPerBlock;
        this.materials = Collections.unmodifiableMap(new LinkedHashMap<>(materials));
        this.animation = animation;
        this.sockets = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(sockets, "sockets")));
        this.extensionsUsed = List.copyOf(Objects.requireNonNull(extensionsUsed, "extensionsUsed"));
    }

    public BlendResourceId descriptorId() {
        return descriptorId;
    }

    public ModelProfile profile() {
        return profile;
    }

    public BlendResourceId meshId() {
        return meshId;
    }

    public double unitsPerBlock() {
        return unitsPerBlock;
    }

    public Map<String, MaterialDefinition> materials() {
        return materials;
    }

    public AnimationDefinition animation() {
        return animation;
    }

    public Map<BlendResourceId, String> sockets() {
        return sockets;
    }

    public List<String> extensionsUsed() {
        return extensionsUsed;
    }
}
