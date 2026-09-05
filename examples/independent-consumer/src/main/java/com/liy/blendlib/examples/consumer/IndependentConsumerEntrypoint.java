package com.liy.blendlib.examples.consumer;

import com.liy.blendlib.api.AnimationRequest;
import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendLib;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.HostRegistrationSpec;
import net.fabricmc.api.ModInitializer;

/**
 * Main-source-only example of a consumer creating a stable immutable binding.
 *
 * <p>No implementation module, platform adapter, client class, resource parser, or renderer is
 * referenced from common initialization.</p>
 */
public final class IndependentConsumerEntrypoint implements ModInitializer {
    public static final BlendModelKey MODEL = BlendModelKey.parse("blendlib_independent_consumer:demo/model");
    public static final BlendAnimationKey IDLE = BlendAnimationKey.parse("blendlib_independent_consumer:idle");
    public static final ConsumerHostToken HOST = new ConsumerHostToken("standalone-demo-host");
    public static final HostRegistrationSpec<ConsumerHostToken> SPECIFICATION = BlendLib.entity(HOST)
            .model(MODEL)
            .animation(AnimationRequest.loop(IDLE))
            .build();

    @Override
    public void onInitialize() {
        System.getLogger("BlendLib Independent Consumer").log(
                System.Logger.Level.INFO,
                "Built semantic registration for {0}; adapter registration is externally owned.",
                SPECIFICATION.model().value());
    }
}
