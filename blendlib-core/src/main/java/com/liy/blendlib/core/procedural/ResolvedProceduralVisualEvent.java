package com.liy.blendlib.core.procedural;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.model.Transform;
import java.util.Objects;

/** Event frozen for presentation; socket effects carry their exact immutable transform at evaluation time. */
public record ResolvedProceduralVisualEvent(
        BlendResourceId id,
        int priority,
        ProceduralVisualEventProvenance provenance,
        ProceduralVisualEventPayload payload,
        Transform socketTransform) {
    public ResolvedProceduralVisualEvent {
        ProceduralSupport.requireId(id, "visual event id");
        provenance = Objects.requireNonNull(provenance, "provenance");
        payload = Objects.requireNonNull(payload, "payload");
        if (payload instanceof ProceduralVisualEventPayload.SocketEffect) {
            socketTransform = Objects.requireNonNull(socketTransform, "socketTransform");
        } else if (socketTransform != null) {
            throw new IllegalArgumentException("only socket effects may carry a socket transform");
        }
    }
}
