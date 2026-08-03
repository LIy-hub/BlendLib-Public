package com.liy.blendlib.showcase.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/** Server-safe P7 host whose client binding will use the frozen 64-joint reference model. */
public final class P7BenchmarkSkinnedEntity extends P7BenchmarkHostEntity {
    public P7BenchmarkSkinnedEntity(EntityType<? extends P7BenchmarkSkinnedEntity> entityType, Level level) {
        super(entityType, level);
    }
}
