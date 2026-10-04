package com.liy.blendlib.examples.runnable;

import com.liy.blendlib.fabric.client.item.BlendLibItemBinding;
import com.liy.blendlib.fabric.client.item.BlendLibItemMorphs;

/** Ordinary marker-item registration; no animation state, event handler or stack retention. */
public final class ExampleCpuMorphItemClient {
    private ExampleCpuMorphItemClient() { }

    public static void register() {
        var binding = new BlendLibItemBinding(ExampleCpuMorphContent.STATIC_ITEM_ID,
                ExampleCpuMorphItemControls.MODEL, ExampleContent.id("item/static_cpu_morph_item"));
        BlendLibItemMorphs.register(binding, stack -> ExampleCpuMorphItemControls.capture(stack.getCount()));
    }
}
