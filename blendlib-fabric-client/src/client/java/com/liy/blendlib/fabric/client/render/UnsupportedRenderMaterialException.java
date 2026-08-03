package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.api.BlendModelKey;
import java.util.Objects;

/** Controlled reload-time failure for a material that the P4 backend cannot represent faithfully. */
@SuppressWarnings("serial") // BlendModelKey deliberately has no Java-serialization contract; this is process-local diagnostics.
public final class UnsupportedRenderMaterialException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    private final BlendModelKey modelKey;
    private final String materialSlot;
    private final MaterialRejectionReason reason;

    public UnsupportedRenderMaterialException(
            BlendModelKey modelKey, String materialSlot, MaterialRejectionReason reason, String message) {
        super(message);
        this.modelKey = Objects.requireNonNull(modelKey, "modelKey");
        this.materialSlot = Objects.requireNonNull(materialSlot, "materialSlot");
        this.reason = Objects.requireNonNull(reason, "reason");
        if (materialSlot.isBlank()) {
            throw new IllegalArgumentException("materialSlot must not be blank");
        }
    }

    public BlendModelKey modelKey() {
        return modelKey;
    }

    public String materialSlot() {
        return materialSlot;
    }

    public MaterialRejectionReason reason() {
        return reason;
    }
}
