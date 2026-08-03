package com.liy.blendlib.fabric.common;

import com.liy.blendlib.core.BlendCoreService;
import com.liy.blendlib.fabric.common.animation.BlendAnimations;
import net.fabricmc.api.ModInitializer;

/**
 * Common entrypoint kept safe for dedicated servers.
 */
public final class BlendLibCommonEntrypoint implements ModInitializer {
    private static final System.Logger LOGGER = System.getLogger("BlendLib");

    @Override
    public void onInitialize() {
        BlendAnimations.initializeCommon();
        LOGGER.log(System.Logger.Level.DEBUG, "Initialized {0}", BlendCoreService.marker());
    }
}
