package com.liy.blendlib.core.procedural;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Objects;

/** Immutable event request at one explicit frame/loop boundary. */
public record ProceduralVisualEvent(
        BlendResourceId id,
        int priority,
        ProceduralVisualEventProvenance provenance,
        ProceduralVisualEventPayload payload) {
    public ProceduralVisualEvent {
        ProceduralSupport.requireId(id, "visual event id");
        provenance = Objects.requireNonNull(provenance, "provenance");
        payload = Objects.requireNonNull(payload, "payload");
    }
}
