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
public final class CpuMorphActor extends Entity {
    private final ExampleCpuMorphControls controls = new ExampleCpuMorphControls();
    public CpuMorphActor(EntityType<? extends CpuMorphActor> type, Level level) { super(type, level); }
    public ExampleCpuMorphControls controls() { return controls; }
    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) { }
    @Override public boolean hurtServer(ServerLevel level, DamageSource source, float amount) { return false; }
    @Override protected void readAdditionalSaveData(ValueInput input) { }
    @Override protected void addAdditionalSaveData(ValueOutput output) { }
}
