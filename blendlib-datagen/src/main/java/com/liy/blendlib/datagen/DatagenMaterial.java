package com.liy.blendlib.datagen;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable strict descriptor material declaration for data generation.
 *
 * <p><strong>Stable boundary:</strong> the base color must be an external {@code textures/*.png}
 * resource. No embedded texture, image decoding, Blender source, or platform material class is
 * accepted by this authoring contract.</p>
 *
 * @param baseColor strict external PNG resource identity
 * @param mode frozen descriptor material mode
 * @param emissive whether descriptor intent requests emissive output
 * @param doubleSided whether descriptor intent requests double-sided output
 * @param cutoutThreshold optional threshold allowed only for cutout mode
 */
public record DatagenMaterial(
        BlendResourceId baseColor,
        DatagenMaterialMode mode,
        boolean emissive,
        boolean doubleSided,
        Optional<Double> cutoutThreshold) {
    /**
     * Validates an immutable strict descriptor material declaration.
     */
    public DatagenMaterial {
        baseColor = Objects.requireNonNull(baseColor, "baseColor");
        if (!baseColor.path().startsWith("textures/") || !baseColor.path().endsWith(".png")) {
            throw new IllegalArgumentException("baseColor must be an external textures/*.png resource");
        }
        mode = Objects.requireNonNull(mode, "mode");
        cutoutThreshold = Objects.requireNonNull(cutoutThreshold, "cutoutThreshold");
        if (cutoutThreshold.isPresent()) {
            double value = cutoutThreshold.get();
            if (!Double.isFinite(value) || value < 0.0 || value > 1.0 || mode != DatagenMaterialMode.CUTOUT) {
                throw new IllegalArgumentException("cutoutThreshold must be finite in [0,1] and use CUTOUT mode");
            }
        }
    }

    /**
     * Creates a default opaque, non-emissive, single-sided material declaration.
     *
     * @param baseColor strict external PNG resource identity
     * @return immutable default material declaration
     */
    public static DatagenMaterial opaque(BlendResourceId baseColor) {
        return new DatagenMaterial(baseColor, DatagenMaterialMode.OPAQUE, false, false, Optional.empty());
    }
}
