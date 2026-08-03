package com.liy.blendlib.showcase.entity;

import com.liy.blendlib.api.BlendAnimationKey;
import java.util.Objects;
import java.util.UUID;

/** Pure package-local cadence/seed policy for the Showcase server semantic trigger. */
final class ShowcaseAnimatedActorAttackSchedule {
    static final BlendAnimationKey ATTACK_ANIMATION_KEY = BlendAnimationKey.parse("blendlib_showcase:attack");
    static final float ATTACK_SPEED = 1.0F;
    static final long ATTACK_TRIGGER_INTERVAL_TICKS = 80L;

    private static final long ATTACK_SEED_SALT = 0x5A17_6C3D_91E2_4B0FL;

    private ShowcaseAnimatedActorAttackSchedule() {
    }

    static boolean shouldTriggerAttackAt(long gameTick) {
        return gameTick >= 0L && gameTick % ATTACK_TRIGGER_INTERVAL_TICKS == 0L;
    }

    static long attackSeed(UUID entityUuid, long gameTick) {
        Objects.requireNonNull(entityUuid, "entityUuid");
        return entityUuid.getMostSignificantBits()
                ^ Long.rotateLeft(entityUuid.getLeastSignificantBits(), 17)
                ^ Long.rotateLeft(gameTick, 7)
                ^ ATTACK_SEED_SALT;
    }
}
