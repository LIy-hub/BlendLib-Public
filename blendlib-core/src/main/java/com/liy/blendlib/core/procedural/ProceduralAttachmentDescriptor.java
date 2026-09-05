package com.liy.blendlib.core.procedural;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.model.Transform;
import java.util.Objects;

/**
 * Frozen semantic attachment descriptor. Child-model topology is intentionally absent: it is derived only by the
 * configuration-time {@link ProceduralAttachmentGraph} from real descriptor ownership.
 */
public record ProceduralAttachmentDescriptor(
        BlendResourceId id,
        ProceduralAttachmentAnchor anchor,
        ProceduralAttachmentPayload payload,
        Transform localTransform,
        boolean visible) {

    public ProceduralAttachmentDescriptor {
        ProceduralSupport.requireId(id, "attachment id");
        anchor = Objects.requireNonNull(anchor, "anchor");
        payload = Objects.requireNonNull(payload, "payload");
        localTransform = Objects.requireNonNull(localTransform, "localTransform");
    }
}
