package com.liy.blendlib.core.profile.experimental;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable X9 descriptor candidate, intentionally separate from v1 ModelDescriptor. */
public final class ExperimentalDescriptor {
    private final BlendResourceId descriptorId;
    private final ExperimentalProfile profile;
    private final BlendResourceId meshId;
    private final double unitsPerBlock;
    private final Map<String, ExperimentalMaterialDefinition> materials;
    private final List<ExperimentalCapabilityRequirement> requiredCapabilities;
    private final List<ExperimentalCapabilityRequirement> optionalCapabilities;

    public ExperimentalDescriptor(
            BlendResourceId descriptorId,
            ExperimentalProfile profile,
            BlendResourceId meshId,
            double unitsPerBlock,
            Map<String, ExperimentalMaterialDefinition> materials,
            List<ExperimentalCapabilityRequirement> requiredCapabilities,
            List<ExperimentalCapabilityRequirement> optionalCapabilities) {
        this.descriptorId = Objects.requireNonNull(descriptorId, "descriptorId");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.meshId = Objects.requireNonNull(meshId, "meshId");
        if (!Double.isFinite(unitsPerBlock) || unitsPerBlock <= 0.0) {
            throw new IllegalArgumentException("unitsPerBlock must be finite and positive");
        }
        Objects.requireNonNull(materials, "materials");
        if (materials.isEmpty()) {
            throw new IllegalArgumentException("An X9 descriptor must define at least one material");
        }
        this.unitsPerBlock = unitsPerBlock;
        this.materials = Collections.unmodifiableMap(new LinkedHashMap<>(materials));
        this.requiredCapabilities = List.copyOf(Objects.requireNonNull(requiredCapabilities, "requiredCapabilities"));
        this.optionalCapabilities = List.copyOf(Objects.requireNonNull(optionalCapabilities, "optionalCapabilities"));
    }

    public BlendResourceId descriptorId() {
        return descriptorId;
    }

    public ExperimentalProfile profile() {
        return profile;
    }

    public BlendResourceId meshId() {
        return meshId;
    }

    public double unitsPerBlock() {
        return unitsPerBlock;
    }

    public Map<String, ExperimentalMaterialDefinition> materials() {
        return materials;
    }

    public List<ExperimentalCapabilityRequirement> requiredCapabilities() {
        return requiredCapabilities;
    }

    public List<ExperimentalCapabilityRequirement> optionalCapabilities() {
        return optionalCapabilities;
    }
}
