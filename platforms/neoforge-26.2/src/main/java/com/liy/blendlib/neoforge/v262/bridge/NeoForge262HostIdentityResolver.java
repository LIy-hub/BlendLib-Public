package com.liy.blendlib.neoforge.v262.bridge;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.api.HostKind;

/**
 * Future-binding hook that reduces a native NeoForge host token to a pure semantic identity.
 *
 * <p><strong>WAITING boundary:</strong> the bridge never guesses reflection, registry internals,
 * or a cross-loader identifier type. A verified NeoForge 26.2 implementation supplies this
 * resolver at its own platform boundary after official API confirmation.</p>
 */
@FunctionalInterface
public interface NeoForge262HostIdentityResolver {
    /**
     * Resolves one native host object into its canonical pure semantic identity.
     *
     * @param hostKind semantic host category
     * @param host opaque future native host token
     * @return canonical host identity
     */
    BlendResourceId resolve(HostKind hostKind, Object host);
}
