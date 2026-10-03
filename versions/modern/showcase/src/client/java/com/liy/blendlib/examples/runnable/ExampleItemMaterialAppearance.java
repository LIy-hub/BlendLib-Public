package com.liy.blendlib.examples.runnable;

import com.liy.blendlib.fabric.client.item.BlendLibItemAnimations;
import com.liy.blendlib.fabric.client.item.BlendLibItemBinding;
import com.liy.blendlib.fabric.client.item.BlendLibItemModelBindings;
import com.liy.blendlib.fabric.client.render.MaterialSlotAppearance;
import java.util.Map;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;

/** Opt-in, extraction-time stack appearance; owns neither meshes nor mutable render state. */
public final class ExampleItemMaterialAppearance {
    public static final String BODY_SLOT = "WandBody";
    public static final String ACCESSORY_SLOT = "WandAccessory";

    private ExampleItemMaterialAppearance() { }

    public static void register(boolean enabled) {
        var binding = new BlendLibItemBinding(ExampleContent.WAND_ID,
                enabled ? ExampleContent.APPEARANCE_WAND_MODEL : ExampleContent.WAND_MODEL,
                ExampleContent.id("item/animated_wand"));
        if (enabled) {
            BlendLibItemModelBindings.register(binding, ExampleItemMaterialAppearance::select);
        }
        // Animation registration retains the selector configured above.
        BlendLibItemAnimations.register(binding, ExampleContent.IDLE);
    }

    public static Map<String, MaterialSlotAppearance> select(ItemStack stack) {
        var name = stack.get(DataComponents.CUSTOM_NAME);
        return forName(name == null ? null : name.getString());
    }

    /** Also exercised by the packaged headless example verification. */
    public static Map<String, MaterialSlotAppearance> forName(String customName) {
        var selected = ExampleMaterialAppearance.forName(customName);
        if (selected.isEmpty()) return Map.of();
        return Map.of(BODY_SLOT, selected.get(ExampleMaterialAppearance.BODY_SLOT),
                ACCESSORY_SLOT, selected.get(ExampleMaterialAppearance.ACCESSORY_SLOT));
    }
}
