package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Objects;

/** Prepared X6 variant behavior. It contains no resource key needing submit-time resolution. */
public sealed interface X6VariantEffect permits X6VariantEffect.Skin, X6VariantEffect.Material,
        X6VariantEffect.Mesh, X6VariantEffect.Equipment, X6VariantEffect.DamageStage,
        X6VariantEffect.PartVisibility {
    /** Replaces only the texture in an already accepted standard material route. */
    record Skin(BlendResourceId textureId) implements X6VariantEffect {
        public Skin {
            textureId = X6Ids.requireId(textureId, "textureId");
        }
    }

    /** Reuses an already prepared exact public-path material. */
    record Material(RenderMaterial material) implements X6VariantEffect {
        public Material {
            material = Objects.requireNonNull(material, "material");
        }
    }

    /**
     * Replaces a part only with another identity already retained by the same prepared geometry
     * catalog. The compiler verifies node/skin compatibility before the draw is published.
     */
    record Mesh(BlendResourceId replacementPartId) implements X6VariantEffect {
        public Mesh {
            replacementPartId = X6Ids.requireId(replacementPartId, "replacementPartId");
        }
    }

    /** Holds an already prepared child snapshot; no model or resource lookup happens later. */
    record Equipment(ModelRenderSnapshot snapshot) implements X6VariantEffect {
        public Equipment {
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    /** Applies a deterministic presentation-only tint to the selected part. */
    record DamageStage(int argbTint) implements X6VariantEffect {
    }

    /** Freezes a final part visibility state before the render plan is built. */
    record PartVisibility(boolean visible) implements X6VariantEffect {
    }
}
