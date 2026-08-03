package com.liy.blendlib.spi.experimental;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Set;

/** Controlled provider of semantic material capability metadata. */
@ExperimentalBlendLibSpi
public interface MaterialProvider extends BlendProvider {
    /**
     * Returns canonical material capability identities without exposing render pipeline objects.
     *
     * @return immutable or snapshot-safe material capability set
     */
    Set<BlendResourceId> supportedMaterialCapabilities();
}
