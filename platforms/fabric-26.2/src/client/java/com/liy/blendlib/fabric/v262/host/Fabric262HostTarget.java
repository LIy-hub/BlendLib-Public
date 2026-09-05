package com.liy.blendlib.fabric.v262.host;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.api.HostKind;
import java.util.Objects;

/**
 * Immutable native-host identity after Fabric 26.2 semantic registration translation.
 *
 * <p><strong>Platform target:</strong> Fabric on Minecraft 26.2. The native registry object is
 * deliberately reduced to a canonical pure identity and semantic host kind before it can cross the
 * X4 host seam. No Minecraft {@code Identifier}, entity, block-entity, or item type escapes this
 * value.</p>
 *
 * @param kind semantic host category
 * @param registryId canonical identity assigned by Minecraft's native registry
 */
public record Fabric262HostTarget(HostKind kind, BlendResourceId registryId) {
    /**
     * Validates a complete translated host target.
     */
    public Fabric262HostTarget {
        kind = Objects.requireNonNull(kind, "kind");
        registryId = Objects.requireNonNull(registryId, "registryId");
    }
}
