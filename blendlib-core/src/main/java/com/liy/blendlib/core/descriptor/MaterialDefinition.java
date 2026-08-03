package com.liy.blendlib.core.descriptor;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Objects;

/** Immutable v1 material intent selected by a descriptor material-slot name. */
public record MaterialDefinition(
        BlendResourceId baseColor,
        Mode mode,
        boolean emissive,
        boolean doubleSided,
        Double cutoutThreshold) {
    public MaterialDefinition {
        baseColor = Objects.requireNonNull(baseColor, "baseColor");
        mode = Objects.requireNonNull(mode, "mode");
        if (cutoutThreshold != null) {
            if (!Double.isFinite(cutoutThreshold) || cutoutThreshold < 0.0 || cutoutThreshold > 1.0) {
                throw new IllegalArgumentException("cutoutThreshold must be finite and between zero and one");
            }
            if (mode != Mode.CUTOUT) {
                throw new IllegalArgumentException("cutoutThreshold is only valid for cutout materials");
            }
        }
    }

    /** Supported non-PBR rendering intents. */
    public enum Mode {
        OPAQUE("opaque"),
        CUTOUT("cutout"),
        TRANSLUCENT("translucent"),
        ADDITIVE("additive");

        private final String serializedName;

        Mode(String serializedName) {
            this.serializedName = serializedName;
        }

        public String serializedName() {
            return serializedName;
        }

        public static Mode fromSerializedName(String value) {
            for (Mode mode : values()) {
                if (mode.serializedName.equals(value)) {
                    return mode;
                }
            }
            throw new IllegalArgumentException("Unsupported v1 material mode: " + value);
        }
    }
}
