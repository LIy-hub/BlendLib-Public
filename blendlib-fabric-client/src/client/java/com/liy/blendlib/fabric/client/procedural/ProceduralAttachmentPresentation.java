package com.liy.blendlib.fabric.client.procedural;

import com.liy.blendlib.core.procedural.ProceduralResolvedAttachment;
import java.util.Objects;

/** Immutable client presentation carrier; platform renderers choose how to display its semantic descriptor. */
public record ProceduralAttachmentPresentation(ProceduralResolvedAttachment attachment) {
    public ProceduralAttachmentPresentation {
        attachment = Objects.requireNonNull(attachment, "attachment");
    }
}
