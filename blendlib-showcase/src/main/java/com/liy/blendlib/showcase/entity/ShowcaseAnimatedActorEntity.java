package com.liy.blendlib.showcase.entity;

import com.liy.blendlib.fabric.common.animation.BlendAnimations;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Server-safe Showcase host with a deterministic semantic attack trigger.
 *
 * <p>The trigger carries only the public animation key, speed, and deterministic seed. This entity
 * intentionally owns no model key, resource path, render reference, controller state, payload
 * implementation, or collision data derived from visual geometry. Its registered fixed dimensions
 * remain the only gameplay shape until a separately designed server feature says otherwise.</p>
 */
public final class ShowcaseAnimatedActorEntity extends Entity {
    private long lastAttackTriggerTick = Long.MIN_VALUE;

    public ShowcaseAnimatedActorEntity(EntityType<? extends ShowcaseAnimatedActorEntity> entityType, Level level) {
        super(entityType, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        // Animation semantics are sent by BlendLib payloads, never by entity data accessors.
    }

    @Override
    public void tick() {
        super.tick();

        if (!(level() instanceof ServerLevel serverLevel)) {
            return;
        }

        long gameTick = serverLevel.getGameTime();
        if (!ShowcaseAnimatedActorAttackSchedule.shouldTriggerAttackAt(gameTick) || lastAttackTriggerTick == gameTick) {
            return;
        }

        BlendAnimations.entity(this).trigger(
                ShowcaseAnimatedActorAttackSchedule.ATTACK_ANIMATION_KEY,
                ShowcaseAnimatedActorAttackSchedule.ATTACK_SPEED,
                ShowcaseAnimatedActorAttackSchedule.attackSeed(getUUID(), gameTick));
        lastAttackTriggerTick = gameTick;
    }

    @Override
    public boolean hurtServer(ServerLevel serverLevel, DamageSource damageSource, float amount) {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        // No visual asset or animation state is persisted on the server.
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        // No visual asset or animation state is persisted on the server.
    }
}
