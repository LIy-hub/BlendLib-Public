package com.liy.blendlib.fabric.client.api;

import com.liy.blendlib.api.BlendModelKey;

/** Read-only view of the atomically published client model generation. */
public interface ClientModelLookup {
    /** Returns an immutable point-in-time view of all discovered model handles. */
    ClientRegistryView snapshot();

    /** Resolves one semantic key against the current generation, returning a stable missing view when absent. */
    ClientModelView resolve(BlendModelKey modelKey);
}
