package com.liy.blendlib.spi.experimental;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Set;

/** Controlled provider of immutable, versioned asset-profile support metadata. */
@ExperimentalBlendLibSpi
public interface AssetProfileProvider extends BlendProvider {
    /**
     * Returns canonical asset-profile identities supported by this provider.
     *
     * @return immutable or snapshot-safe profile identity set
     */
    Set<BlendResourceId> supportedAssetProfiles();
}
