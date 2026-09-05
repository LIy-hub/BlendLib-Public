package com.liy.blendlib.examples.ecosystem;

import com.liy.blendlib.examples.ecosystem.block.ExampleBlocks;
import com.liy.blendlib.examples.ecosystem.blockentity.ExampleBlockEntities;
import com.liy.blendlib.examples.ecosystem.entity.ExampleEntities;
import com.liy.blendlib.examples.ecosystem.item.ExampleItems;
import com.liy.blendlib.fabric.common.animation.BlendAnimations;
import net.fabricmc.api.ModInitializer;

/**
 * Common, server-safe entrypoint for the independent public-API example.
 *
 * <p>The entrypoint never loads a client renderer, parses an asset, resolves a socket, or installs
 * an Experimental platform adapter. It publishes only normal content and public semantic animation
 * services.</p>
 */
public final class EcosystemExampleEntrypoint implements ModInitializer {
    private static final System.Logger LOGGER = System.getLogger("BlendLib Ecosystem Example");

    @Override
    public void onInitialize() {
        ExampleBlocks.initialize();
        ExampleBlockEntities.initialize();
        ExampleEntities.initialize();
        ExampleItems.initialize();
        BlendAnimations.initializeCommon();
        int stableSpecifications = ExampleStableBindings.specifications().size();
        LOGGER.log(System.Logger.Level.INFO,
                "Prepared {0} immutable BlendLib semantic registrations; platform adapter installation remains externally owned.",
                stableSpecifications);
    }
}
