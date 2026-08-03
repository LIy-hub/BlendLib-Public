package com.liy.blendlib.core.asset;

import com.liy.blendlib.api.BlendResourceId;

/**
 * Resolves an already-authorized BlendLib resource into immutable bytes.
 *
 * <p>This interface deliberately has no {@code Path}, URI, class loader, or
 * Minecraft type. Platform code owns all I/O and authorization decisions.</p>
 */
@FunctionalInterface
public interface AssetResolver {
    AssetBytes resolve(BlendResourceId resourceId);
}
