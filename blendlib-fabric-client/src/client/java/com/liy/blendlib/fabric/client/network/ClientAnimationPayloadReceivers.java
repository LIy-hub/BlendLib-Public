package com.liy.blendlib.fabric.client.network;

import com.liy.blendlib.fabric.client.animation.sync.ClientAnimationSyncRuntime;
import com.liy.blendlib.fabric.common.network.BlockEntityAnimationPayload;
import com.liy.blendlib.fabric.common.network.EntityAnimationPayload;
import java.util.Objects;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.multiplayer.ClientLevel;

/** Registers clientbound animation receivers and explicitly transfers every mutation to the client executor. */
public final class ClientAnimationPayloadReceivers {
    private static boolean registered;

    private ClientAnimationPayloadReceivers() {
    }

    public static synchronized void register(ClientAnimationSyncRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime");
        if (registered) {
            return;
        }
        boolean entityRegistered = ClientPlayNetworking.registerGlobalReceiver(EntityAnimationPayload.TYPE,
                (payload, context) -> {
                    var client = context.client();
                    ClientLevel receivedLevel = client.level;
                    ReceiveTimeLevelEpochDelivery.defer(
                            receivedLevel,
                            client::execute,
                            () -> client.level,
                            level -> runtime.receive(payload, level));
                });
        boolean blockEntityRegistered = ClientPlayNetworking.registerGlobalReceiver(BlockEntityAnimationPayload.TYPE,
                (payload, context) -> {
                    var client = context.client();
                    ClientLevel receivedLevel = client.level;
                    ReceiveTimeLevelEpochDelivery.defer(
                            receivedLevel,
                            client::execute,
                            () -> client.level,
                            level -> runtime.receive(payload, level));
                });
        if (!entityRegistered || !blockEntityRegistered) {
            throw new IllegalStateException("BlendLib animation payload receiver was already registered by another owner");
        }
        registered = true;
    }
}
