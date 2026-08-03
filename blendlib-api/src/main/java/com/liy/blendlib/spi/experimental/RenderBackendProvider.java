package com.liy.blendlib.spi.experimental;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Set;

/** Controlled provider of adapter-bound render backend capability metadata. */
@ExperimentalBlendLibSpi
public interface RenderBackendProvider extends BlendProvider {
    /**
     * Returns canonical backend capability identities without leaking backend handles.
     *
     * @return immutable or snapshot-safe backend identity set
     */
    Set<BlendResourceId> supportedRenderBackends();
}
