package com.liy.blendlib.fabric.client.entity;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.fabric.client.api.ClientModelLookup;
import net.minecraft.world.phys.AABB;

/** Test-only bridge to the same pre-extraction culling union called by the ordinary entity renderer. */
public final class RunnableAssemblyCullingVerification {
    private RunnableAssemblyCullingVerification() { }

    public static AABB bounds(ClientModelLookup models, BlendModelKey key, double x, double y, double z,
            boolean rotationInvariant, BlendEntityCullingEnvelope envelope) {
        return EntityCullingBounds.unionWithCurrentModelBounds(models, key,
                new AABB(x - .4, y, z - .4, x + .4, y + 1.5, z + .4),
                x, y, z, rotationInvariant, envelope);
    }
}
