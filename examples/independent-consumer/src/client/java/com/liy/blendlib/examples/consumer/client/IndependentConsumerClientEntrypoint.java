package com.liy.blendlib.examples.consumer.client;

import com.liy.blendlib.api.BlendResourceId;
import net.fabricmc.api.ClientModInitializer;

/** Client source exists independently while keeping this minimal consumer free of renderer internals. */
public final class IndependentConsumerClientEntrypoint implements ClientModInitializer {
    private static final BlendResourceId PRESENTATION_CHANNEL =
            BlendResourceId.parse("blendlib_independent_consumer:client_presentation");

    @Override
    public void onInitializeClient() {
        System.getLogger("BlendLib Independent Consumer Client").log(
                System.Logger.Level.DEBUG,
                "Client semantic channel is {0}; rendering integration remains opt-in.",
                PRESENTATION_CHANNEL.value());
    }
}
