package com.liy.blendlib.examples.runnable;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Stationary by default, summonable presentation host. The server publishes an upper-layer cue every five
 * seconds using ordinary entity data. This is consumer-owned timing, not a new BlendLib protocol.
 * Gameplay dimensions and damage never depend on visual geometry, animation, or sockets.
 */
public final class LayeredActor extends Entity {
    /** Explicit per-actor opt-in to the server-owned demonstration trajectory. */
    public static final String LOCOMOTION_TAG = "blendlib_locomotion";
    private static final EntityDataAccessor<Float> LOCOMOTION_SPEED =
            SynchedEntityData.defineId(LayeredActor.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> LOCOMOTION_GROUNDED =
            SynchedEntityData.defineId(LayeredActor.class, EntityDataSerializers.BOOLEAN);
    private boolean wasLocomotionDemo;
    private static final EntityDataAccessor<Integer> CUE_SEQUENCE =
            SynchedEntityData.defineId(LayeredActor.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Long> CUE_TICK =
            SynchedEntityData.defineId(LayeredActor.class, EntityDataSerializers.LONG);
    // Client callback evidence belongs to this object, not a global numeric entity-ID map.
    // It is deliberately neither saved nor synchronized; the server leaves it empty.
    private final ExampleLayerVisualEvents visualEvents = new ExampleLayerVisualEvents();

    public LayeredActor(EntityType<? extends LayeredActor> type, Level level) {
        super(type, level);
    }

    @Override
    public void tick() {
        super.tick();
        if (level() instanceof ServerLevel) tickLocomotionDemo();
        if (level() instanceof ServerLevel && (tickCount == 1 || tickCount % 100 == 0)) {
            int previous = entityData.get(CUE_SEQUENCE);
            if (previous < Integer.MAX_VALUE) {
                entityData.set(CUE_TICK, level().getGameTime());
                entityData.set(CUE_SEQUENCE, previous + 1);
            }
        }
    }

    private void tickLocomotionDemo() {
        boolean blendSpace = entityTags().contains(ExampleBlendSpaceMotion.TAG);
        if (!blendSpace && !entityTags().contains(LOCOMOTION_TAG)) {
            if (wasLocomotionDemo) setDeltaMovement(Vec3.ZERO);
            wasLocomotionDemo = false;
            entityData.set(LOCOMOTION_SPEED, 0F);
            entityData.set(LOCOMOTION_GROUNDED, onGround());
            return;
        }
        wasLocomotionDemo = true;
        int phase = Math.floorMod(tickCount, 240);
        int segment = phase % 120;
        double speed = segment < 40 ? 0 : segment < 80 ? 0.065 : 0.18;
        double dx = blendSpace ? ExampleBlendSpaceMotion.requestedHorizontalVelocity(tickCount)
                : phase < 120 ? speed : -speed;
        double dy = Math.max(-0.6, getDeltaMovement().y - 0.08);
        double oldX = getX(), oldZ = getZ();
        setDeltaMovement(dx, dy, 0);
        move(MoverType.SELF, getDeltaMovement());
        if (onGround()) setDeltaMovement(dx, 0, 0);
        // Publish actual collision-resolved displacement, not the scripted requested speed.
        // Vanilla tracked entity data avoids client interpolation/network-update threshold jitter.
        entityData.set(LOCOMOTION_SPEED, (float) Math.hypot(getX() - oldX, getZ() - oldZ));
        entityData.set(LOCOMOTION_GROUNDED, onGround());
    }

    public float locomotionSpeed() { return entityData.get(LOCOMOTION_SPEED); }
    public boolean locomotionGrounded() { return entityData.get(LOCOMOTION_GROUNDED); }

    public int cueSequence() { return entityData.get(CUE_SEQUENCE); }
    public long cueTick() { return entityData.get(CUE_TICK); }
    public ExampleLayerVisualEvents visualEvents() { return visualEvents; }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(LOCOMOTION_SPEED, 0F);
        builder.define(LOCOMOTION_GROUNDED, false);
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
