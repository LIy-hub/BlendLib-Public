package com.liy.blendlib.fabric.v262.host;

import com.liy.blendlib.api.AnimationRequest;
import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.api.ModelInstance;
import com.liy.blendlib.core.animation.runtime.AnimationController;
import com.liy.blendlib.fabric.v262.model.Fabric262PoseSnapshot;
import com.liy.blendlib.fabric.v262.model.Fabric262PreparedModelHandle;
import com.liy.blendlib.fabric.v262.model.Fabric262RenderSnapshot;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Private extraction-side owner of actual Fabric 26.2 host animation instances.
 *
 * <p>The stable registration's opaque host token is intentionally not reused as a live native
 * host. After the dispatcher evaluates that source against its original token, this registry
 * creates independent {@link ModelInstance}/{@link AnimationController} pairs for observed entity
 * and block-entity identities. It holds those identities weakly, bounds each collection, clears
 * old generations before rebinding, and never retains a world or an {@code ItemStack}. Item
 * animation remains {@link BlendInstanceKey.Item#STATELESS} and uses a transient loop controller
 * at monotonic extraction time.</p>
 */
final class Fabric262HostAnimationRegistry implements AutoCloseable {
    private static final int MAX_ENTITY_STATES = 1_024;
    private static final int MAX_BLOCK_ENTITY_STATES = 1_024;
    private static final double TICKS_PER_SECOND = 20.0d;

    private final Object monitor = new Object();
    private final List<EntityState> entityStates = new ArrayList<>();
    private final List<BlockEntityState> blockEntityStates = new ArrayList<>();
    private long nextEntitySession;
    private boolean closed;

    /** Freezes one independently advanced entity pose for the supplied open snapshot generation. */
    Fabric262PoseSnapshot extractEntity(
            Entity entity,
            Fabric262RenderSnapshot snapshot,
            AnimationRequest request,
            double observedTicks) {
        Entity checkedEntity = Objects.requireNonNull(entity, "entity");
        Fabric262RenderSnapshot checkedSnapshot = Objects.requireNonNull(snapshot, "snapshot");
        AnimationRequest checkedRequest = Objects.requireNonNull(request, "request");
        requireObservedTicks(observedTicks);
        Fabric262PreparedModelHandle handle = checkedSnapshot.preparedHandle();
        Object level = Objects.requireNonNull(checkedEntity.level(), "entity.level");
        synchronized (monitor) {
            requireOpen();
            retireOtherGenerations(checkedSnapshot.generation());
            EntityState state = findEntityState(checkedEntity);
            if (state == null) {
                ensureEntityCapacity();
                state = new EntityState(checkedEntity);
                entityStates.add(state);
            }
            if (!state.matches(level, checkedEntity.getId(), checkedSnapshot, checkedRequest)) {
                state.rebind(
                        level,
                        checkedEntity.getId(),
                        checkedSnapshot.modelKey(),
                        checkedSnapshot.generation(),
                        checkedRequest,
                        handle,
                        "fabric262-entity-session-" + (++nextEntitySession));
            }
            return handle.advanceAndFreeze(state.controller, state.advanceDelta(observedTicks));
        }
    }

    /** Freezes one independently advanced block-entity pose for the supplied open snapshot generation. */
    Fabric262PoseSnapshot extractBlockEntity(
            BlockEntity blockEntity,
            Fabric262RenderSnapshot snapshot,
            AnimationRequest request,
            double observedTicks) {
        BlockEntity checkedBlockEntity = Objects.requireNonNull(blockEntity, "blockEntity");
        Fabric262RenderSnapshot checkedSnapshot = Objects.requireNonNull(snapshot, "snapshot");
        AnimationRequest checkedRequest = Objects.requireNonNull(request, "request");
        requireObservedTicks(observedTicks);
        Fabric262PreparedModelHandle handle = checkedSnapshot.preparedHandle();
        Level level = Objects.requireNonNull(checkedBlockEntity.getLevel(), "blockEntity.level");
        BlendResourceId dimension = BlendResourceId.parse(
                level.dimension().identifier().toString());
        long packedPosition = checkedBlockEntity.getBlockPos().asLong();
        synchronized (monitor) {
            requireOpen();
            retireOtherGenerations(checkedSnapshot.generation());
            BlockEntityState state = findBlockEntityState(checkedBlockEntity);
            if (state == null) {
                ensureBlockEntityCapacity();
                state = new BlockEntityState(checkedBlockEntity);
                blockEntityStates.add(state);
            }
            if (!state.matches(level, dimension, packedPosition, checkedSnapshot, checkedRequest)) {
                state.rebind(
                        level,
                        dimension,
                        packedPosition,
                        checkedSnapshot.modelKey(),
                        checkedSnapshot.generation(),
                        checkedRequest,
                        handle);
            }
            return handle.advanceAndFreeze(state.controller, state.advanceDelta(observedTicks));
        }
    }

    /**
     * Freezes a loop-only stateless item pose with no retained item stack, world, or playhead.
     */
    Fabric262PoseSnapshot extractStatelessItem(
            Fabric262RenderSnapshot snapshot,
            AnimationRequest request,
            double observedSeconds) {
        Fabric262RenderSnapshot checkedSnapshot = Objects.requireNonNull(snapshot, "snapshot");
        AnimationRequest checkedRequest = Objects.requireNonNull(request, "request");
        if (!Double.isFinite(observedSeconds) || observedSeconds < 0.0d) {
            throw new IllegalArgumentException("Observed item animation time must be finite and non-negative");
        }
        synchronized (monitor) {
            requireOpen();
            Fabric262PreparedModelHandle handle = checkedSnapshot.preparedHandle();
            ModelInstance instance = new ModelInstance(
                    BlendInstanceKey.Item.STATELESS,
                    checkedSnapshot.modelKey(),
                    checkedSnapshot.generation());
            return handle.sampleStatelessLoop(instance, checkedRequest, observedSeconds);
        }
    }

    /** Clears every live weak-slot/controller at exact dispatcher terminal cleanup. */
    @Override
    public void close() {
        synchronized (monitor) {
            if (closed) {
                return;
            }
            closed = true;
            entityStates.clear();
            blockEntityStates.clear();
        }
    }

    private EntityState findEntityState(Entity entity) {
        Iterator<EntityState> iterator = entityStates.iterator();
        while (iterator.hasNext()) {
            EntityState state = iterator.next();
            Entity observed = state.host.get();
            if (observed == null) {
                iterator.remove();
            } else if (observed == entity) {
                return state;
            }
        }
        return null;
    }

    private BlockEntityState findBlockEntityState(BlockEntity blockEntity) {
        Iterator<BlockEntityState> iterator = blockEntityStates.iterator();
        while (iterator.hasNext()) {
            BlockEntityState state = iterator.next();
            BlockEntity observed = state.host.get();
            if (observed == null) {
                iterator.remove();
            } else if (observed == blockEntity) {
                return state;
            }
        }
        return null;
    }

    private void retireOtherGenerations(long activeGeneration) {
        entityStates.removeIf(state -> state.instance != null
                && state.instance.resourceGeneration() != activeGeneration);
        blockEntityStates.removeIf(state -> state.instance != null
                && state.instance.resourceGeneration() != activeGeneration);
    }

    private void ensureEntityCapacity() {
        while (entityStates.size() >= MAX_ENTITY_STATES) {
            entityStates.remove(0);
        }
    }

    private void ensureBlockEntityCapacity() {
        while (blockEntityStates.size() >= MAX_BLOCK_ENTITY_STATES) {
            blockEntityStates.remove(0);
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Fabric 26.2 host animation state is closed");
        }
    }

    private static void requireObservedTicks(double observedTicks) {
        if (!Double.isFinite(observedTicks) || observedTicks < 0.0d) {
            throw new IllegalArgumentException("Observed extraction time must be finite and non-negative");
        }
    }

    private abstract static class HostState<H> {
        protected final WeakReference<H> host;
        protected WeakReference<Object> level = new WeakReference<>(null);
        protected ModelInstance instance;
        protected AnimationRequest request;
        protected AnimationController controller;
        protected double lastObservedTicks = Double.NaN;

        private HostState(H host) {
            this.host = new WeakReference<>(Objects.requireNonNull(host, "host"));
        }

        protected final boolean commonMatches(
                Object observedLevel,
                BlendModelKey modelKey,
                long generation,
                AnimationRequest observedRequest) {
            return level.get() == observedLevel
                    && instance != null
                    && instance.modelKey().equals(modelKey)
                    && instance.resourceGeneration() == generation
                    && request.equals(observedRequest)
                    && controller != null;
        }

        protected final double advanceDelta(double observedTicks) {
            double deltaTicks = Double.isNaN(lastObservedTicks)
                    ? 0.0d
                    : Math.max(0.0d, observedTicks - lastObservedTicks);
            lastObservedTicks = observedTicks;
            return deltaTicks / TICKS_PER_SECOND;
        }

        protected final void bindCommon(
                Object observedLevel,
                ModelInstance replacement,
                AnimationRequest replacementRequest,
                Fabric262PreparedModelHandle handle,
                AnimationController priorController) {
            ModelInstance checkedReplacement = Objects.requireNonNull(replacement, "replacement");
            AnimationRequest checkedRequest = Objects.requireNonNull(replacementRequest, "replacementRequest");
            AnimationController replacementController = Objects.requireNonNull(handle, "handle")
                    .createAnimationController(checkedReplacement, checkedRequest, priorController);
            level = new WeakReference<>(Objects.requireNonNull(observedLevel, "observedLevel"));
            instance = checkedReplacement;
            request = checkedRequest;
            controller = replacementController;
            lastObservedTicks = Double.NaN;
        }
    }

    private static final class EntityState extends HostState<Entity> {
        private int entityId = -1;
        private String sessionToken;

        private EntityState(Entity entity) {
            super(entity);
        }

        private boolean matches(
                Object level,
                int entityId,
                Fabric262RenderSnapshot snapshot,
                AnimationRequest request) {
            return this.entityId == entityId
                    && commonMatches(level, snapshot.modelKey(), snapshot.generation(), request);
        }

        private void rebind(
                Object level,
                int entityId,
                BlendModelKey modelKey,
                long generation,
                AnimationRequest request,
                Fabric262PreparedModelHandle handle,
                String freshSessionToken) {
            AnimationController priorController = instance != null
                    && instance.modelKey().equals(modelKey)
                    && instance.resourceGeneration() == generation
                    ? controller
                    : null;
            if (sessionToken == null || this.entityId != entityId || this.level.get() != level) {
                sessionToken = Objects.requireNonNull(freshSessionToken, "freshSessionToken");
            }
            this.entityId = entityId;
            bindCommon(
                    level,
                    new ModelInstance(new BlendInstanceKey.Entity(sessionToken, entityId), modelKey, generation),
                    request,
                    handle,
                    priorController);
        }
    }

    private static final class BlockEntityState extends HostState<BlockEntity> {
        private BlendResourceId dimension;
        private long packedPosition;

        private BlockEntityState(BlockEntity blockEntity) {
            super(blockEntity);
        }

        private boolean matches(
                Object level,
                BlendResourceId dimension,
                long packedPosition,
                Fabric262RenderSnapshot snapshot,
                AnimationRequest request) {
            return Objects.equals(this.dimension, dimension)
                    && this.packedPosition == packedPosition
                    && commonMatches(level, snapshot.modelKey(), snapshot.generation(), request);
        }

        private void rebind(
                Object level,
                BlendResourceId dimension,
                long packedPosition,
                BlendModelKey modelKey,
                long generation,
                AnimationRequest request,
                Fabric262PreparedModelHandle handle) {
            AnimationController priorController = instance != null
                    && instance.modelKey().equals(modelKey)
                    && instance.resourceGeneration() == generation
                    ? controller
                    : null;
            this.dimension = Objects.requireNonNull(dimension, "dimension");
            this.packedPosition = packedPosition;
            bindCommon(
                    level,
                    new ModelInstance(
                            new BlendInstanceKey.BlockEntity(this.dimension, packedPosition), modelKey, generation),
                    request,
                    handle,
                    priorController);
        }
    }
}
