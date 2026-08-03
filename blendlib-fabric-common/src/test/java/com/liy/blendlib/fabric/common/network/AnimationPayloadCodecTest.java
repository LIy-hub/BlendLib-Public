package com.liy.blendlib.fabric.common.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.fabric.common.animation.SyncedAnimationState;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import org.junit.jupiter.api.Test;

class AnimationPayloadCodecTest {
    private static final SyncedAnimationState STATE =
            new SyncedAnimationState(BlendAnimationKey.parse("blendlib_test:attack"), 123L, 7L, 1.25F, 99L, true);

    @Test
    void entityPayloadRoundTripsOnlyTheDeclaredSemanticFields() {
        RegistryFriendlyByteBuf buffer = buffer();
        EntityAnimationPayload expected = new EntityAnimationPayload(42, STATE);

        EntityAnimationPayload.CODEC.encode(buffer, expected);

        assertEquals(expected, EntityAnimationPayload.CODEC.decode(buffer));
        assertEquals(EntityAnimationPayload.TYPE, expected.type());
    }

    @Test
    void blockEntityPayloadRoundTripsOnlyTheDeclaredSemanticFields() {
        RegistryFriendlyByteBuf buffer = buffer();
        BlockEntityAnimationPayload expected = new BlockEntityAnimationPayload(new BlockPos(12, 64, -8), STATE);

        BlockEntityAnimationPayload.CODEC.encode(buffer, expected);

        assertEquals(expected, BlockEntityAnimationPayload.CODEC.decode(buffer));
        assertEquals(BlockEntityAnimationPayload.TYPE, expected.type());
    }

    @Test
    void constructorsAndCodecRejectBoundViolationsAndMalformedValues() {
        assertThrows(IllegalArgumentException.class, () -> new EntityAnimationPayload(-1, STATE));
        for (float invalidSpeed : new float[] {
                Float.NaN,
                Float.NEGATIVE_INFINITY,
                Float.POSITIVE_INFINITY,
                -1.0F,
                0.0F,
                Math.nextUp(SyncedAnimationState.MAX_SPEED)}) {
            assertThrows(IllegalArgumentException.class, () -> new SyncedAnimationState(
                    BlendAnimationKey.parse("blendlib_test:bad_speed"), 0L, 0L, invalidSpeed, 0L, false));
        }
        assertThrows(IllegalArgumentException.class, () -> new SyncedAnimationState(
                BlendAnimationKey.parse("blendlib_test:" + "a".repeat(SyncedAnimationState.MAX_ANIMATION_KEY_UTF8_BYTES)),
                0L, 0L, 1.0F, 0L, false));

        RegistryFriendlyByteBuf malformedStartTick = buffer();
        malformedStartTick.writeVarInt(3);
        ByteBufCodecs.stringUtf8(SyncedAnimationState.MAX_ANIMATION_KEY_UTF8_BYTES)
                .encode(malformedStartTick, "blendlib_test:idle");
        ByteBufCodecs.VAR_LONG.encode(malformedStartTick, -1L);
        ByteBufCodecs.VAR_LONG.encode(malformedStartTick, 0L);
        ByteBufCodecs.FLOAT.encode(malformedStartTick, 1.0F);
        ByteBufCodecs.VAR_LONG.encode(malformedStartTick, 0L);
        ByteBufCodecs.BOOL.encode(malformedStartTick, false);

        IllegalArgumentException rejection = assertThrows(
                IllegalArgumentException.class, () -> EntityAnimationPayload.CODEC.decode(malformedStartTick));
        assertTrue(rejection.getMessage().contains("startGameTick"));

        RegistryFriendlyByteBuf malformedEntityId = buffer();
        malformedEntityId.writeVarInt(-1);
        writeRawState(malformedEntityId, "blendlib_test:idle", 0L, 0L, 1.0F, 0L, false);
        assertThrows(IllegalArgumentException.class, () -> EntityAnimationPayload.CODEC.decode(malformedEntityId));

        RegistryFriendlyByteBuf malformedSpeed = buffer();
        malformedSpeed.writeVarInt(3);
        writeRawState(malformedSpeed, "blendlib_test:idle", 0L, 0L, Float.NaN, 0L, false);
        assertThrows(IllegalArgumentException.class, () -> EntityAnimationPayload.CODEC.decode(malformedSpeed));

        RegistryFriendlyByteBuf malformedKey = buffer();
        malformedKey.writeVarInt(3);
        writeRawState(malformedKey, "BlendLib_Test:idle", 0L, 0L, 1.0F, 0L, false);
        assertThrows(IllegalArgumentException.class, () -> EntityAnimationPayload.CODEC.decode(malformedKey));
    }

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    }

    private static void writeRawState(
            RegistryFriendlyByteBuf buffer,
            String animationKey,
            long startGameTick,
            long sequence,
            float speed,
            long seed,
            boolean persistent) {
        ByteBufCodecs.stringUtf8(SyncedAnimationState.MAX_ANIMATION_KEY_UTF8_BYTES).encode(buffer, animationKey);
        ByteBufCodecs.VAR_LONG.encode(buffer, startGameTick);
        ByteBufCodecs.VAR_LONG.encode(buffer, sequence);
        ByteBufCodecs.FLOAT.encode(buffer, speed);
        ByteBufCodecs.VAR_LONG.encode(buffer, seed);
        ByteBufCodecs.BOOL.encode(buffer, persistent);
    }
}
