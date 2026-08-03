package com.liy.blendlib.fabric.client.render;

import java.util.Objects;

/** Result of mapping descriptor material intent into the P4 standard render-layer subset. */
public sealed interface MaterialMapping permits MaterialMapping.Supported, MaterialMapping.Rejected {
    /** A material supported by the standard P4 backend. */
    record Supported(RenderMaterial material) implements MaterialMapping {
        public Supported {
            material = Objects.requireNonNull(material, "material");
        }
    }

    /** A material deliberately rejected rather than rendered with incorrect semantics. */
    record Rejected(MaterialRejectionReason reason, String message) implements MaterialMapping {
        public Rejected {
            reason = Objects.requireNonNull(reason, "reason");
            message = Objects.requireNonNull(message, "message");
            if (message.isBlank()) {
                throw new IllegalArgumentException("message must not be blank");
            }
        }
    }
}
