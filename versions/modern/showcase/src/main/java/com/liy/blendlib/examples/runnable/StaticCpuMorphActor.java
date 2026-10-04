package com.liy.blendlib.examples.runnable;

import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** Dedicated stationary host. Presentation controls never change collision, damage, or saved data. */
public final class StaticCpuMorphActor extends Entity {
    public StaticCpuMorphActor(EntityType<? extends StaticCpuMorphActor> type, Level level) { super(type, level); }
    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) { }
    @Override public boolean hurtServer(ServerLevel level, DamageSource source, float amount) { return false; }
    @Override protected void readAdditionalSaveData(ValueInput input) { }
    @Override protected void addAdditionalSaveData(ValueOutput output) { }
}
