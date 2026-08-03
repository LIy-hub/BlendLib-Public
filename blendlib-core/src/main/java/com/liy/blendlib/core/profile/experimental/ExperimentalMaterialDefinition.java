package com.liy.blendlib.core.profile.experimental;

import com.liy.blendlib.api.BlendResourceId;
import java.util.List;
import java.util.Objects;

/**
 * Immutable descriptor-side candidate for richer material metadata.
 *
 * <p>This is validation metadata only. It does not enable a Minecraft render
 * pipeline, PBR path, shader, KTX2, or custom GPU behavior.</p>
 */
public record ExperimentalMaterialDefinition(
        BlendResourceId baseColor,
        Mode mode,
        boolean doubleSided,
        double metallicFactor,
        double roughnessFactor,
        BlendResourceId normalTexture,
        BlendResourceId occlusionTexture,
        BlendResourceId emissiveTexture,
        List<Double> emissiveFactor,
        Double alphaCutoff) {
    public ExperimentalMaterialDefinition {
        baseColor = Objects.requireNonNull(baseColor, "baseColor");
        mode = Objects.requireNonNull(mode, "mode");
        if (!Double.isFinite(metallicFactor) || metallicFactor < 0.0 || metallicFactor > 1.0
                || !Double.isFinite(roughnessFactor) || roughnessFactor < 0.0 || roughnessFactor > 1.0) {
            throw new IllegalArgumentException("Material metallic and roughness factors must be finite values in [0, 1]");
        }
        emissiveFactor = List.copyOf(Objects.requireNonNull(emissiveFactor, "emissiveFactor"));
        if (emissiveFactor.size() != 3 || emissiveFactor.stream().anyMatch(value -> value == null || !Double.isFinite(value)
                || value < 0.0 || value > 1.0)) {
            throw new IllegalArgumentException("Material emissive factor must contain three finite values in [0, 1]");
        }
        if (alphaCutoff != null && (!Double.isFinite(alphaCutoff) || alphaCutoff < 0.0 || alphaCutoff > 1.0
                || mode != Mode.CUTOUT)) {
            throw new IllegalArgumentException("alphaCutoff is finite in [0, 1] and valid only for cutout materials");
        }
    }

    public enum Mode {
        OPAQUE("opaque"),
        CUTOUT("cutout"),
        TRANSLUCENT("translucent");

        private final String serializedName;

        Mode(String serializedName) {
            this.serializedName = serializedName;
        }

        public String serializedName() {
            return serializedName;
        }

        public static Mode fromSerializedName(String value) {
            for (Mode mode : values()) {
                if (mode.serializedName.equals(value)) {
                    return mode;
                }
            }
            throw new IllegalArgumentException("Unsupported X9 material mode: " + value);
        }
    }
}
