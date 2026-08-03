package com.liy.blendlib.api;

/**
 * Semantic category of a platform host registration.
 *
 * <p>The enum intentionally does not expose a platform host type. A version-specific adapter
 * translates the generic host supplied by a consumer after registration has been validated.</p>
 */
public enum HostKind {
    /** A living or non-living world entity host. */
    ENTITY,

    /** A block-attached host with a persistent block position. */
    BLOCK_ENTITY,

    /** An item-model host. */
    ITEM
}
