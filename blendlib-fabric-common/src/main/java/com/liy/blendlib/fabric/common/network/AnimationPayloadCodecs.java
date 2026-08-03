package com.liy.blendlib.fabric.common.network;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.fabric.common.animation.SyncedAnimationState;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;

/** Internal bounded codec helpers shared by BlendLib's clientbound animation payloads. */
final class AnimationPayloadCodecs {
    private static final int MAX_ANIMATION_KEY_UTF8_BYTES = SyncedAnimationState.MAX_ANIMATION_KEY_UTF8_BYTES;

    private AnimationPayloadCodecs() {
    }

    static void encodeState(RegistryFriendlyByteBuf buffer, SyncedAnimationState state) {
        ByteBufCodecs.stringUtf8(MAX_ANIMATION_KEY_UTF8_BYTES).encode(buffer, state.animationKey().value());
        ByteBufCodecs.VAR_LONG.encode(buffer, state.startGameTick());
        ByteBufCodecs.VAR_LONG.encode(buffer, state.sequence());
        ByteBufCodecs.FLOAT.encode(buffer, state.speed());
        ByteBufCodecs.VAR_LONG.encode(buffer, state.seed());
        ByteBufCodecs.BOOL.encode(buffer, state.persistent());
    }

    static SyncedAnimationState decodeState(RegistryFriendlyByteBuf buffer) {
        String animationKey = ByteBufCodecs.stringUtf8(MAX_ANIMATION_KEY_UTF8_BYTES).decode(buffer);
        long startGameTick = ByteBufCodecs.VAR_LONG.decode(buffer);
        long sequence = ByteBufCodecs.VAR_LONG.decode(buffer);
        float speed = ByteBufCodecs.FLOAT.decode(buffer);
        long seed = ByteBufCodecs.VAR_LONG.decode(buffer);
        boolean persistent = ByteBufCodecs.BOOL.decode(buffer);
        return new SyncedAnimationState(
                BlendAnimationKey.parse(animationKey), startGameTick, sequence, speed, seed, persistent);
    }
}
