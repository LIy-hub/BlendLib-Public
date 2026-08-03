package com.liy.blendlib.showcase.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/** Server-safe P7 host whose client binding will use the frozen rigid reference model. */
public final class P7BenchmarkRigidEntity extends P7BenchmarkHostEntity {
    public P7BenchmarkRigidEntity(EntityType<? extends P7BenchmarkRigidEntity> entityType, Level level) {
        super(entityType, level);
    }
}
