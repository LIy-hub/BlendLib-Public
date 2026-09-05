package com.liy.blendlib.fabric.client.host;

import com.liy.blendlib.api.HostKind;

/**
 * Existing Fabric seam that owns the final renderer installation for one stable X1 host kind.
 *
 * <p>X4 records this semantic routing only. It deliberately does not register a renderer from a
 * stable {@link HostKind} token: doing so would require changing the existing client entrypoint
 * and renderer registries, which is an integration-owned operation documented in the X4 handoff.</p>
 */
public enum X4StableHostSeam {
    /** Existing entity renderer registration path. */
    ENTITY_RENDERER,

    /** Existing block-entity renderer registration path. */
    BLOCK_ENTITY_RENDERER,

    /** Existing ordinary marker-item model path; it is intentionally not the X4 held-item target. */
    MARKER_ITEM_RENDERER;

    static X4StableHostSeam from(HostKind hostKind) {
        return switch (hostKind) {
            case ENTITY -> ENTITY_RENDERER;
            case BLOCK_ENTITY -> BLOCK_ENTITY_RENDERER;
            case ITEM -> MARKER_ITEM_RENDERER;
        };
    }
}
