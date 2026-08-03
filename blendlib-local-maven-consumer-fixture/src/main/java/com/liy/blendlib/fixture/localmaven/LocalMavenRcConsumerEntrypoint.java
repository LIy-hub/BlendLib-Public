package com.liy.blendlib.fixture.localmaven;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import net.fabricmc.api.ModInitializer;

/**
 * Blank consumer proof that a separately built Fabric mod can use the local BlendLib RC through
 * only public semantic API classes.
 */
public final class LocalMavenRcConsumerEntrypoint implements ModInitializer {
    public static final BlendModelKey MODEL = BlendModelKey.parse("local_maven_consumer:blank_model");
    public static final BlendAnimationKey IDLE = BlendAnimationKey.parse("local_maven_consumer:idle");

    @Override
    public void onInitialize() {
        // Key construction is deliberately semantic and I/O-free; no BlendLib implementation type is reachable here.
        BlendResourceId descriptor = MODEL.descriptorResourceId();
        if (!descriptor.value().equals("local_maven_consumer:blend_models/blank_model.json")) {
            throw new IllegalStateException("Unexpected public BlendLib descriptor mapping: " + descriptor);
        }
    }
}
