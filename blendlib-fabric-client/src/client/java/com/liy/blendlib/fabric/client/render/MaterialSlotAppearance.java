package com.liy.blendlib.fabric.client.render;

/** Immutable RGB-only multiplier and visibility for one exact authored material slot. */
public record MaterialSlotAppearance(int rgbTint, boolean visible) {
    private static final MaterialSlotAppearance UNCHANGED = new MaterialSlotAppearance(0xffffff, true);

    public MaterialSlotAppearance {
        if ((rgbTint & 0xff000000) != 0) {
            throw new IllegalArgumentException("rgbTint must be an unsigned 24-bit RGB multiplier");
        }
    }

    public static MaterialSlotAppearance unchanged() { return UNCHANGED; }
}
