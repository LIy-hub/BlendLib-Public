package com.liy.blendlib.fabric.common.animation;

import com.liy.blendlib.api.BlendAnimationKey;
import java.util.Objects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Public server-side facade for BlendLib v1 semantic animation synchronization.
 *
 * <p>Calls accept only an animation key plus speed and seed. They never accept a model, GLB, matrix, material, or
 * gameplay hit/collision decision.</p>
 */
public final class BlendAnimations {
    private static final ServerAnimationSyncService SERVICE = new ServerAnimationSyncService();

    private BlendAnimations() {
    }

    /** Registers payloads and server tracking hooks during the common mod entrypoint's initialization. */
    public static void initializeCommon() {
        SERVICE.initialize();
    }

    /** Starts an entity-targeted semantic animation command builder. */
    public static EntityAnimationTarget entity(Entity entity) {
        return new EntityAnimationTarget(entity);
    }

    /** Starts a block-entity-targeted semantic animation command builder. */
    public static BlockEntityAnimationTarget blockEntity(BlockEntity blockEntity) {
        return new BlockEntityAnimationTarget(blockEntity);
    }

    /** Public entity command target retaining no model or render state. */
    public static final class EntityAnimationTarget {
        private final Entity entity;

        private EntityAnimationTarget(Entity entity) {
            this.entity = Objects.requireNonNull(entity, "entity");
        }

        public SyncedAnimationState trigger(BlendAnimationKey animationKey) {
            return trigger(animationKey, 1.0F, 0L);
        }

        public SyncedAnimationState trigger(BlendAnimationKey animationKey, float speed, long seed) {
            return SERVICE.trigger(entity, Objects.requireNonNull(animationKey, "animationKey"), speed, seed);
        }

        public SyncedAnimationState setPersistent(BlendAnimationKey animationKey) {
            return setPersistent(animationKey, 1.0F, 0L);
        }

        public SyncedAnimationState setPersistent(BlendAnimationKey animationKey, float speed, long seed) {
            return SERVICE.setPersistent(entity, Objects.requireNonNull(animationKey, "animationKey"), speed, seed);
        }
    }

    /** Public block-entity command target retaining no model or render state. */
    public static final class BlockEntityAnimationTarget {
        private final BlockEntity blockEntity;

        private BlockEntityAnimationTarget(BlockEntity blockEntity) {
            this.blockEntity = Objects.requireNonNull(blockEntity, "blockEntity");
        }

        public SyncedAnimationState trigger(BlendAnimationKey animationKey) {
            return trigger(animationKey, 1.0F, 0L);
        }

        public SyncedAnimationState trigger(BlendAnimationKey animationKey, float speed, long seed) {
            return SERVICE.trigger(blockEntity, Objects.requireNonNull(animationKey, "animationKey"), speed, seed);
        }

        public SyncedAnimationState setPersistent(BlendAnimationKey animationKey) {
            return setPersistent(animationKey, 1.0F, 0L);
        }

        public SyncedAnimationState setPersistent(BlendAnimationKey animationKey, float speed, long seed) {
            return SERVICE.setPersistent(blockEntity, Objects.requireNonNull(animationKey, "animationKey"), speed, seed);
        }
    }
}
