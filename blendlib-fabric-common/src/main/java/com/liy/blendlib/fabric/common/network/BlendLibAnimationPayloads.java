package com.liy.blendlib.fabric.common.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

/** Registers BlendLib's fixed, clientbound-only v1 animation payload types. */
public final class BlendLibAnimationPayloads {
    private static boolean registered;

    private BlendLibAnimationPayloads() {
    }

    /**
     * Registers both payload codecs exactly once during common mod initialization, before play connections begin.
     */
    public static synchronized void registerClientbound() {
        if (registered) {
            return;
        }
        PayloadTypeRegistry.clientboundPlay().register(EntityAnimationPayload.TYPE, EntityAnimationPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(BlockEntityAnimationPayload.TYPE, BlockEntityAnimationPayload.CODEC);
        registered = true;
    }
}
