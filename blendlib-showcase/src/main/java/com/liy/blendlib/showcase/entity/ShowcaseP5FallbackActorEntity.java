package com.liy.blendlib.showcase.entity;

import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Server-safe host for the local-only P5 fallback-schedule fixture.
 *
 * <p>This fixture carries neither visual asset data nor animation state across the server
 * boundary. The client-only binding owns its deterministic schedule, so this entity cannot
 * trigger, retain, or synchronize animation semantics.</p>
 */
public final class ShowcaseP5FallbackActorEntity extends Entity {
    public ShowcaseP5FallbackActorEntity(
            EntityType<? extends ShowcaseP5FallbackActorEntity> entityType, Level level) {
        super(entityType, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        // The fixture deliberately defines no synchronized visual or animation state.
    }

    @Override
    public boolean hurtServer(ServerLevel serverLevel, DamageSource damageSource, float amount) {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        // This stateless display fixture persists no visual asset or schedule data.
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        // This stateless display fixture persists no visual asset or schedule data.
    }
}
