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
 * Minimal, non-gameplay entity used solely to host the P4 static-rigid client rendering path.
 *
 * <p>It has no synchronized animation state, collision derived from model geometry, damage logic,
 * or server-side resource access. P5/P6 functionality is deliberately absent.</p>
 */
public final class ShowcaseRigidEntity extends Entity {
    public ShowcaseRigidEntity(EntityType<? extends ShowcaseRigidEntity> entityType, Level level) {
        super(entityType, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        // P4 static rest pose carries no per-instance synchronized model state.
    }

    @Override
    public boolean hurtServer(ServerLevel serverLevel, DamageSource damageSource, float amount) {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        // This deliberately stateless display entity persists no visual asset data.
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        // This deliberately stateless display entity persists no visual asset data.
    }
}
