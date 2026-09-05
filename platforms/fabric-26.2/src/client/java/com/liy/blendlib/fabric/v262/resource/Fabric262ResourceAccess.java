package com.liy.blendlib.fabric.v262.resource;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.asset.AssetBytes;
import com.liy.blendlib.core.asset.AssetResolver;
import java.util.List;

/**
 * Platform-owned authorized resource view used only during a Fabric 26.2 reload.
 *
 * <p><strong>Platform target:</strong> Fabric on Minecraft 26.2. This is the sole bridge from
 * the Fabric resource manager to the pure {@link AssetResolver} contract. Callers must not retain
 * it after preparation; submit paths receive immutable prepared handles instead of this I/O-capable
 * object.</p>
 */
public interface Fabric262ResourceAccess extends AssetResolver {
    /**
     * Discovers canonical descriptor keys in deterministic ascending key order.
     *
     * @return immutable deterministic model-key list
     */
    List<BlendModelKey> discoverModels();

    /**
     * Reads the one descriptor mandated by a semantic model key.
     *
     * @param key strict semantic model key
     * @return immutable descriptor bytes with the exact descriptor resource identity
     */
    AssetBytes descriptor(BlendModelKey key);

    /**
     * Resolves one already-authorized asset during reload preparation.
     *
     * @param resourceId strict resource identity selected by the decoded descriptor
     * @return immutable exact resource bytes
     */
    @Override
    AssetBytes resolve(BlendResourceId resourceId);
}
