package com.liy.blendlib.examples.runnable;

import com.liy.blendlib.fabric.client.render.MaterialSlotAppearance;
import java.util.Locale;
import java.util.Map;

/** Per-instance presentation selected from the host's client-synchronized custom name. */
public final class ExampleMaterialAppearance {
    public static final String BODY_SLOT = "ShowcaseAnimationSurface";
    public static final String ACCESSORY_SLOT = "ExampleAccessory";

    private ExampleMaterialAppearance() {
    }

    /** Unknown or absent names deliberately leave every authored material unchanged. */
    public static Map<String, MaterialSlotAppearance> forName(String customName) {
        if (customName == null) {
            return Map.of();
        }
        return switch (customName.toLowerCase(Locale.ROOT)) {
            case "orange" -> selection(0xff8844, true);
            case "orange bare" -> selection(0xff8844, false);
            case "blue" -> selection(0x4488ff, true);
            case "blue bare" -> selection(0x4488ff, false);
            default -> Map.of();
        };
    }

    private static Map<String, MaterialSlotAppearance> selection(int bodyRgb, boolean accessoryVisible) {
        return Map.of(
                BODY_SLOT, new MaterialSlotAppearance(bodyRgb, true),
                ACCESSORY_SLOT, new MaterialSlotAppearance(0xffffff, accessoryVisible));
    }
}
