package com.liy.blendlib.fabric.common.network;

import com.liy.blendlib.fabric.common.animation.SyncedAnimationState;
import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Strict clientbound semantic animation command for one tracked entity. */
public record EntityAnimationPayload(int entityId, SyncedAnimationState animation) implements CustomPacketPayload {
    public static final Type<EntityAnimationPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("blendlib", "entity_animation"));
    public static final StreamCodec<RegistryFriendlyByteBuf, EntityAnimationPayload> CODEC =
            StreamCodec.of(EntityAnimationPayload::encode, EntityAnimationPayload::decode);

    public EntityAnimationPayload {
        if (entityId < 0) {
            throw new IllegalArgumentException("entityId must be non-negative");
        }
        animation = Objects.requireNonNull(animation, "animation");
    }

    @Override
    public Type<EntityAnimationPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buffer, EntityAnimationPayload payload) {
        buffer.writeVarInt(payload.entityId());
        AnimationPayloadCodecs.encodeState(buffer, payload.animation());
    }

    private static EntityAnimationPayload decode(RegistryFriendlyByteBuf buffer) {
        return new EntityAnimationPayload(buffer.readVarInt(), AnimationPayloadCodecs.decodeState(buffer));
    }
}
