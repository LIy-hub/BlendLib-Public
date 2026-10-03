package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.api.BlendResourceId;

/** Shared reload-time texture substitution, retaining every authored material flag. */
final class TextureOnlyMaterial {
    private TextureOnlyMaterial() {}

    static RenderMaterial replace(RenderMaterial material, BlendResourceId textureId) {
        return new RenderMaterial(textureId, material.layer(), material.emissive(), material.doubleSided(),
                material.argbTint(), material.missingModelMaterial());
    }
}
