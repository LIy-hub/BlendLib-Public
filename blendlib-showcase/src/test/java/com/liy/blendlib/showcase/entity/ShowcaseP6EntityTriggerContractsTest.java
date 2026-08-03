package com.liy.blendlib.showcase.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendAnimationKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ShowcaseP6EntityTriggerContractsTest {
    @Test
    void attackCadenceIsDeterministicAndNeverEmitsEveryTick() {
        assertFalse(ShowcaseAnimatedActorAttackSchedule.shouldTriggerAttackAt(-1L));
        assertTrue(ShowcaseAnimatedActorAttackSchedule.shouldTriggerAttackAt(0L));
        assertFalse(ShowcaseAnimatedActorAttackSchedule.shouldTriggerAttackAt(1L));
        assertFalse(ShowcaseAnimatedActorAttackSchedule.shouldTriggerAttackAt(
                ShowcaseAnimatedActorAttackSchedule.ATTACK_TRIGGER_INTERVAL_TICKS - 1L));
        assertTrue(ShowcaseAnimatedActorAttackSchedule.shouldTriggerAttackAt(
                ShowcaseAnimatedActorAttackSchedule.ATTACK_TRIGGER_INTERVAL_TICKS));
        assertFalse(ShowcaseAnimatedActorAttackSchedule.shouldTriggerAttackAt(
                ShowcaseAnimatedActorAttackSchedule.ATTACK_TRIGGER_INTERVAL_TICKS + 1L));
    }

    @Test
    void attackTriggerUsesTheCanonicalSemanticKeySpeedAndDeterministicSeed() {
        UUID firstEntity = UUID.fromString("8a535ca5-09f4-4d72-b22d-458fd9b0f13f");
        UUID secondEntity = UUID.fromString("8a535ca5-09f4-4d72-b22d-458fd9b0f140");
        long gameTick = ShowcaseAnimatedActorAttackSchedule.ATTACK_TRIGGER_INTERVAL_TICKS;

        assertEquals(BlendAnimationKey.parse("blendlib_showcase:attack"),
                ShowcaseAnimatedActorAttackSchedule.ATTACK_ANIMATION_KEY);
        assertEquals(1.0F, ShowcaseAnimatedActorAttackSchedule.ATTACK_SPEED);
        assertEquals(ShowcaseAnimatedActorAttackSchedule.attackSeed(firstEntity, gameTick),
                ShowcaseAnimatedActorAttackSchedule.attackSeed(firstEntity, gameTick));
        assertNotEquals(ShowcaseAnimatedActorAttackSchedule.attackSeed(firstEntity, gameTick),
                ShowcaseAnimatedActorAttackSchedule.attackSeed(secondEntity, gameTick));
    }

    @Test
    void serverEntitySourceUsesOnlyThePublicSemanticFacade() throws IOException {
        String source = Files.readString(Path.of(
                System.getProperty("blendlib.projectDir"),
                "src", "main", "java", "com", "liy", "blendlib", "showcase", "entity",
                "ShowcaseAnimatedActorEntity.java"));
        String schedule = Files.readString(Path.of(
                System.getProperty("blendlib.projectDir"),
                "src", "main", "java", "com", "liy", "blendlib", "showcase", "entity",
                "ShowcaseAnimatedActorAttackSchedule.java"));

        assertTrue(source.contains("BlendAnimations.entity(this).trigger("));
        assertTrue(source.contains("ShowcaseAnimatedActorAttackSchedule.ATTACK_ANIMATION_KEY"));
        assertTrue(source.contains("if (!(level() instanceof ServerLevel serverLevel))"));
        assertTrue(source.contains("ShowcaseAnimatedActorAttackSchedule.shouldTriggerAttackAt(gameTick)"));
        assertTrue(schedule.contains("gameTick % ATTACK_TRIGGER_INTERVAL_TICKS"));
        assertFalse(source.contains("setPersistent("));
        for (String forbidden : List.of(
                "net.minecraft.client.",
                "com.liy.blendlib.core.",
                "com.liy.blendlib.fabric.client.",
                "com.liy.blendlib.fabric.common.network.",
                "ModelRenderSnapshot",
                "ModelAsset",
                "ResourceManager",
                "FriendlyByteBuf",
                "CustomPacketPayload")) {
            assertFalse(source.contains(forbidden), forbidden);
        }
    }
}
