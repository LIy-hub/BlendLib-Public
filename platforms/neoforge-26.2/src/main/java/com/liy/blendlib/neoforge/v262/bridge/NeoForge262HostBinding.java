package com.liy.blendlib.neoforge.v262.bridge;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.api.HostKind;
import java.util.Objects;

/**
 * Immutable platform-neutral host-binding result prepared for a future NeoForge 26.2 adapter.
 *
 * <p><strong>WAITING boundary:</strong> the binding deliberately includes only semantic values.
 * It cannot create a renderer, register an event handler, or make a loader claim until a verified
 * official NeoForge 26.2 binder consumes it.</p>
 *
 * @param hostKind semantic host category
 * @param hostId canonical future native host identity
 * @param modelKey strict selected model key
 */
public record NeoForge262HostBinding(
        HostKind hostKind,
        BlendResourceId hostId,
        BlendModelKey modelKey) {
    /**
     * Validates a complete pure host-binding result.
     */
    public NeoForge262HostBinding {
        hostKind = Objects.requireNonNull(hostKind, "hostKind");
        hostId = Objects.requireNonNull(hostId, "hostId");
        modelKey = Objects.requireNonNull(modelKey, "modelKey");
    }
}
