package com.liy.blendlib.neoforge.v262.bridge;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.asset.AssetBytes;
import com.liy.blendlib.core.asset.AssetResolver;
import java.util.List;

/**
 * Platform-neutral authorized resource source for the NeoForge 26.2 bridge.
 *
 * <p><strong>WAITING boundary:</strong> an eventual official NeoForge binder implements this
 * interface using verified public APIs. The bridge itself owns no filesystem, URI, resource
 * manager, Fabric, NeoForge, or Minecraft type and is therefore buildable before that binding.</p>
 */
public interface NeoForge262ResourceSource extends AssetResolver {
    /**
     * Discovers strict semantic model keys in deterministic ascending order.
     *
     * @return immutable deterministic key list
     */
    List<BlendModelKey> discoverModels();

    /**
     * Reads the strict descriptor selected by one semantic key during preparation only.
     *
     * @param key strict semantic model key
     * @return immutable exact descriptor bytes
     */
    AssetBytes descriptor(BlendModelKey key);

    /**
     * Resolves a descriptor-authorized strict resource during preparation only.
     *
     * @param resourceId strict referenced resource identity
     * @return immutable exact resource bytes
     */
    @Override
    AssetBytes resolve(BlendResourceId resourceId);
}
