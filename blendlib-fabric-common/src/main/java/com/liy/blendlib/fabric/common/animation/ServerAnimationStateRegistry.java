package com.liy.blendlib.fabric.common.animation;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendResourceId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Server-thread-owned semantic animation state and monotonic sequence allocator.
 *
 * <p>Persistent state is retained separately from transient triggers. A tracking-start replay is assigned a fresh
 * sequence, so a re-tracking client can never discard its snapshot merely because it previously observed a newer
 * transient command.</p>
 */
public final class ServerAnimationStateRegistry {
    private final Map<UUID, StateSlot> entityStates = new HashMap<>();
    private final Map<BlockAnimationTarget, StateSlot> blockStates = new HashMap<>();

    /** Issues one transient command for an entity without replacing its replayable persistent state. */
    public SyncedAnimationState triggerEntity(
            UUID entityUuid, BlendAnimationKey animationKey, long startGameTick, float speed, long seed) {
        return issueEntity(entityUuid, animationKey, startGameTick, speed, seed, false);
    }

    /** Issues and retains one persistent entity state for future tracking-start replay. */
    public SyncedAnimationState setPersistentEntity(
            UUID entityUuid, BlendAnimationKey animationKey, long startGameTick, float speed, long seed) {
        return issueEntity(entityUuid, animationKey, startGameTick, speed, seed, true);
    }

    /** Issues one transient command for a block entity without replacing its replayable persistent state. */
    public SyncedAnimationState triggerBlockEntity(
            BlendResourceId dimension, long packedBlockPos, BlendAnimationKey animationKey, long startGameTick, float speed, long seed) {
        return issueBlockEntity(dimension, packedBlockPos, animationKey, startGameTick, speed, seed, false);
    }

    /** Issues and retains one persistent block-entity state for future tracking-start replay. */
    public SyncedAnimationState setPersistentBlockEntity(
            BlendResourceId dimension, long packedBlockPos, BlendAnimationKey animationKey, long startGameTick, float speed, long seed) {
        return issueBlockEntity(dimension, packedBlockPos, animationKey, startGameTick, speed, seed, true);
    }

    /**
     * Returns a fresh-sequenced persistent entity snapshot, if one exists.
     *
     * <p>The returned replay is intentionally not the historical command object: fresh sequencing prevents an
     * existing client from treating a tracking restart as stale after it observed a later transient.</p>
     */
    public Optional<SyncedAnimationState> replayPersistentEntity(UUID entityUuid) {
        Objects.requireNonNull(entityUuid, "entityUuid");
        return replayPersistent(entityStates.get(entityUuid));
    }

    /** Returns a fresh-sequenced persistent block-entity snapshot, if one exists. */
    public Optional<SyncedAnimationState> replayPersistentBlockEntity(BlendResourceId dimension, long packedBlockPos) {
        return replayPersistent(blockStates.get(new BlockAnimationTarget(dimension, packedBlockPos)));
    }

    /** Returns immutable identifiers for all persistent block states in one server level. */
    public List<PersistentBlockAnimation> persistentBlocksIn(BlendResourceId dimension) {
        Objects.requireNonNull(dimension, "dimension");
        List<PersistentBlockAnimation> result = new ArrayList<>();
        for (Map.Entry<BlockAnimationTarget, StateSlot> entry : blockStates.entrySet()) {
            BlockAnimationTarget target = entry.getKey();
            SyncedAnimationState persistent = entry.getValue().persistentState;
            if (target.dimension.equals(dimension) && persistent != null) {
                result.add(new PersistentBlockAnimation(target.dimension, target.packedBlockPos, persistent));
            }
        }
        return List.copyOf(result);
    }

    /** Removes every retained sequence and persistent state for an entity that has been permanently discarded. */
    public void clearEntity(UUID entityUuid) {
        entityStates.remove(Objects.requireNonNull(entityUuid, "entityUuid"));
    }

    /** Removes all retained state for one unloaded or removed block entity. */
    public void clearBlockEntity(BlendResourceId dimension, long packedBlockPos) {
        blockStates.remove(new BlockAnimationTarget(dimension, packedBlockPos));
    }

    /** Removes all block-entity state belonging to one unloaded server level. */
    public void clearDimension(BlendResourceId dimension) {
        Objects.requireNonNull(dimension, "dimension");
        blockStates.keySet().removeIf(target -> target.dimension.equals(dimension));
    }

    /** Clears every retained target at server shutdown or a fully replaced server lifecycle. */
    public void clearAll() {
        entityStates.clear();
        blockStates.clear();
    }

    /** Visible for bounded-state and cleanup tests. */
    public int entityTargetCount() {
        return entityStates.size();
    }

    /** Visible for bounded-state and cleanup tests. */
    public int blockEntityTargetCount() {
        return blockStates.size();
    }

    private SyncedAnimationState issueEntity(
            UUID entityUuid,
            BlendAnimationKey animationKey,
            long startGameTick,
            float speed,
            long seed,
            boolean persistent) {
        Objects.requireNonNull(entityUuid, "entityUuid");
        return issue(entityStates.computeIfAbsent(entityUuid, ignored -> new StateSlot()),
                animationKey, startGameTick, speed, seed, persistent);
    }

    private SyncedAnimationState issueBlockEntity(
            BlendResourceId dimension,
            long packedBlockPos,
            BlendAnimationKey animationKey,
            long startGameTick,
            float speed,
            long seed,
            boolean persistent) {
        BlockAnimationTarget target = new BlockAnimationTarget(dimension, packedBlockPos);
        return issue(blockStates.computeIfAbsent(target, ignored -> new StateSlot()),
                animationKey, startGameTick, speed, seed, persistent);
    }

    private static SyncedAnimationState issue(
            StateSlot slot,
            BlendAnimationKey animationKey,
            long startGameTick,
            float speed,
            long seed,
            boolean persistent) {
        SyncedAnimationState emitted = new SyncedAnimationState(
                animationKey, startGameTick, slot.nextSequence(), speed, seed, persistent);
        if (persistent) {
            slot.persistentState = emitted;
        }
        return emitted;
    }

    private static Optional<SyncedAnimationState> replayPersistent(StateSlot slot) {
        if (slot == null || slot.persistentState == null) {
            return Optional.empty();
        }
        SyncedAnimationState replay = slot.persistentState.asPersistent().withSequence(slot.nextSequence());
        slot.persistentState = replay;
        return Optional.of(replay);
    }

    /** One immutable persistent block-target/state snapshot for server tracking observation. */
    public record PersistentBlockAnimation(BlendResourceId dimension, long packedBlockPos, SyncedAnimationState animation) {
        public PersistentBlockAnimation {
            dimension = Objects.requireNonNull(dimension, "dimension");
            animation = Objects.requireNonNull(animation, "animation").asPersistent();
        }
    }

    private record BlockAnimationTarget(BlendResourceId dimension, long packedBlockPos) {
        private BlockAnimationTarget {
            dimension = Objects.requireNonNull(dimension, "dimension");
        }
    }

    private static final class StateSlot {
        private long lastSequence;
        private SyncedAnimationState persistentState;

        private long nextSequence() {
            if (lastSequence == Long.MAX_VALUE) {
                throw new IllegalStateException("animation sequence exhausted; clear the target before issuing another command");
            }
            return ++lastSequence;
        }
    }
}
