package com.liy.blendlib.core.procedural;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Objects;

/** Attachment anchors are semantic and contain neither Minecraft nor renderer objects. */
public sealed interface ProceduralAttachmentAnchor permits ProceduralAttachmentAnchor.Bone,
        ProceduralAttachmentAnchor.Socket {

    record Bone(int boneIndex) implements ProceduralAttachmentAnchor {
        public Bone {
            if (boneIndex < 0) {
                throw new IllegalArgumentException("attachment bone index must be non-negative");
            }
        }
    }

    record Socket(BlendResourceId socketId) implements ProceduralAttachmentAnchor {
        public Socket {
            socketId = Objects.requireNonNull(socketId, "socketId");
            ProceduralSupport.requireId(socketId, "attachment socket id");
        }
    }
}
