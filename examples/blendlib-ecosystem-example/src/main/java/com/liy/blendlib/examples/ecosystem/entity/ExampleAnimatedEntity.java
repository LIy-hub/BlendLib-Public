package com.liy.blendlib.examples.ecosystem.entity;

import com.liy.blendlib.examples.ecosystem.ExampleKeys;
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
 * Minimal server-owned host whose visual attack cue remains semantic and presentation-only.
 *
 * <p>Its dimensions, collision, damage result, and persistence do not derive from GLB geometry,
 * sockets, animation state, or a client model.</p>
 */
public final class ExampleAnimatedEntity extends Entity {
    public ExampleAnimatedEntity(EntityType<? extends ExampleAnimatedEntity> type, Level level) {
        super(type, level);
    }

    /**
     * Publishes one semantic animation event from authoritative gameplay code.
     *
     * <p>Calling code chooses when an attack is valid. BlendLib transports only the animation key,
     * bounded speed, and deterministic seed; it never decides gameplay.</p>
     */
    public void publishAttackPresentation(long seed) {
        if (level() instanceof ServerLevel) {
            BlendAnimations.entity(this).trigger(ExampleKeys.ATTACK, 1.0F, seed);
        }
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        // Semantic animation synchronization is not entity data and has no model fields.
    }

    @Override
    public boolean hurtServer(ServerLevel serverLevel, DamageSource damageSource, float amount) {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        // No visual resource or animation state is persisted by this example host.
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        // No visual resource or animation state is persisted by this example host.
    }
}
