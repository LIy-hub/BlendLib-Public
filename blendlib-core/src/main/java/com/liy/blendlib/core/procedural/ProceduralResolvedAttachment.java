package com.liy.blendlib.core.procedural;

import com.liy.blendlib.core.model.Transform;
import java.util.Objects;

/** Deep-frozen attachment descriptor plus its final model-space anchor transform for presentation-only consumption. */
public record ProceduralResolvedAttachment(ProceduralAttachmentDescriptor descriptor, Transform anchorTransform) {
    public ProceduralResolvedAttachment {
        descriptor = Objects.requireNonNull(descriptor, "descriptor");
        anchorTransform = Objects.requireNonNull(anchorTransform, "anchorTransform");
    }
}
