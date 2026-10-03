package com.liy.blendlib.examples.runnable;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Stationary, summonable presentation host. The server publishes an upper-layer cue every five
 * seconds using ordinary entity data. This is consumer-owned timing, not a new BlendLib protocol.
 * Gameplay dimensions and damage never depend on visual geometry, animation, or sockets.
 */
public final class LayeredActor extends Entity {
    private static final EntityDataAccessor<Integer> CUE_SEQUENCE =
            SynchedEntityData.defineId(LayeredActor.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Long> CUE_TICK =
            SynchedEntityData.defineId(LayeredActor.class, EntityDataSerializers.LONG);

    public LayeredActor(EntityType<? extends LayeredActor> type, Level level) {
        super(type, level);
    }

    @Override
    public void tick() {
        super.tick();
        if (level() instanceof ServerLevel && (tickCount == 1 || tickCount % 100 == 0)) {
            int previous = entityData.get(CUE_SEQUENCE);
            if (previous < Integer.MAX_VALUE) {
                entityData.set(CUE_TICK, level().getGameTime());
                entityData.set(CUE_SEQUENCE, previous + 1);
            }
        }
    }

    public int cueSequence() { return entityData.get(CUE_SEQUENCE); }
    public long cueTick() { return entityData.get(CUE_TICK); }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(CUE_SEQUENCE, 0);
        builder.define(CUE_TICK, 0L);
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) { return false; }
    @Override
    protected void readAdditionalSaveData(ValueInput input) { }
    @Override
    protected void addAdditionalSaveData(ValueOutput output) { }
}
