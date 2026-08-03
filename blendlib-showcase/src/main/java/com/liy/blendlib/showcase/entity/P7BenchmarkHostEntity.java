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
 * Server-safe base for the opt-in P7 benchmark hosts.
 *
 * <p>The hosts carry no model key, mesh data, animation state, collision derived from a visual
 * asset, or client reference. They exist only after the explicit benchmark command creates them
 * in an isolated development world. The client adapter owns the later model-key binding.</p>
 */
abstract class P7BenchmarkHostEntity extends Entity {
    P7BenchmarkHostEntity(EntityType<?> entityType, Level level) {
        super(entityType, level);
    }

    @Override
    protected final void defineSynchedData(SynchedEntityData.Builder builder) {
        // The benchmark's model binding stays client-side; no visual state crosses the server boundary.
    }

    @Override
    public final boolean hurtServer(ServerLevel serverLevel, DamageSource damageSource, float amount) {
        return false;
    }

    @Override
    protected final void readAdditionalSaveData(ValueInput input) {
        // The scene is regenerated only through the explicit command and persists no visual data.
    }

    @Override
    protected final void addAdditionalSaveData(ValueOutput output) {
        // The scene is regenerated only through the explicit command and persists no visual data.
    }
}
