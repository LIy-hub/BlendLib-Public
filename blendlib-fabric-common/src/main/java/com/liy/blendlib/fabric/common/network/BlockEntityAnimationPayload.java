package com.liy.blendlib.fabric.common.network;

import com.liy.blendlib.fabric.common.animation.SyncedAnimationState;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Strict clientbound semantic animation command for one block entity in the recipient's current dimension. */
public record BlockEntityAnimationPayload(BlockPos blockPos, SyncedAnimationState animation) implements CustomPacketPayload {
    public static final Type<BlockEntityAnimationPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("blendlib", "block_entity_animation"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BlockEntityAnimationPayload> CODEC =
            StreamCodec.of(BlockEntityAnimationPayload::encode, BlockEntityAnimationPayload::decode);

    public BlockEntityAnimationPayload {
        blockPos = Objects.requireNonNull(blockPos, "blockPos").immutable();
        animation = Objects.requireNonNull(animation, "animation");
    }

    @Override
    public Type<BlockEntityAnimationPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buffer, BlockEntityAnimationPayload payload) {
        BlockPos.STREAM_CODEC.encode(buffer, payload.blockPos());
        AnimationPayloadCodecs.encodeState(buffer, payload.animation());
    }

    private static BlockEntityAnimationPayload decode(RegistryFriendlyByteBuf buffer) {
        return new BlockEntityAnimationPayload(BlockPos.STREAM_CODEC.decode(buffer), AnimationPayloadCodecs.decodeState(buffer));
    }
}
