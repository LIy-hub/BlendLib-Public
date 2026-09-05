package com.liy.blendlib.fabric.client.procedural;

/** Presentation-only void seam for ITEM, BLOCK, and child-model semantic attachments. */
@FunctionalInterface
public interface ProceduralAttachmentPresentationResolver {
    void present(ProceduralAttachmentPresentation attachment);
}
