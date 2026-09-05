package com.liy.blendlib.showcase;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.showcase.block.ShowcaseBlocks;
import com.liy.blendlib.showcase.blockentity.ShowcaseBlockEntities;
import com.liy.blendlib.showcase.item.ShowcaseItems;
import net.fabricmc.api.ModInitializer;

/**
 * Independent P4 consumer that deliberately uses only BlendLib's public semantic API.
 */
public final class BlendLibShowcaseEntrypoint implements ModInitializer {
    public static final BlendModelKey STATIC_RIGID_MODEL =
            BlendModelKey.parse("blendlib_showcase:fixtures/static_model");

    private static final System.Logger LOGGER = System.getLogger("BlendLib Showcase");

    @Override
    public void onInitialize() {
        ShowcaseBlocks.initialize();
        ShowcaseBlockEntities.initialize();
        ShowcaseItems.initialize();
        LOGGER.log(System.Logger.Level.INFO, "Showcase registered public static rigid model key {0}", STATIC_RIGID_MODEL.value());
    }
}
