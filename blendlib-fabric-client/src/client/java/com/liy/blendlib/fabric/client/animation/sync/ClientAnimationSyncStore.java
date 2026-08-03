package com.liy.blendlib.fabric.client.animation.sync;

import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.fabric.common.animation.SyncedAnimationState;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Single-client-thread store for the latest accepted semantic animation state.
 *
 * <p>Entity commands are converted to full {@link BlendInstanceKey.Entity} values only after a connection epoch is
 * active. Block entity commands are accepted only for the current dimension. This prevents a bare entity id or
 * bare block position from crossing a reconnect or dimension transition.</p>
 */
public final class ClientAnimationSyncStore {
    private final Map<BlendInstanceKey, SyncedAnimationState> states = new HashMap<>();
    private String connectionSession;
    private BlendResourceId activeDimension;

    /** Starts a fresh connection/dimension epoch and retires all old semantic state. */
    public void beginSession(String newConnectionSession, BlendResourceId newActiveDimension) {
        if (newConnectionSession == null || newConnectionSession.isBlank()) {
            throw new IllegalArgumentException("newConnectionSession must not be blank");
        }
        connectionSession = newConnectionSession;
        activeDimension = Objects.requireNonNull(newActiveDimension, "newActiveDimension");
        states.clear();
    }

    /** Clears all state after the client disconnects. */
    public void disconnect() {
        states.clear();
        connectionSession = null;
        activeDimension = null;
    }

    /** Applies a resolved command only when it has a strictly newer target-local sequence. */
    public ApplyResult apply(ClientAnimationCommand command) {
        Objects.requireNonNull(command, "command");
        BlendInstanceKey key = toInstanceKey(command.target());
        if (key == null) {
            return ApplyResult.OUT_OF_SESSION_DROPPED;
        }
        SyncedAnimationState current = states.get(key);
        if (current != null && command.animation().sequence() <= current.sequence()) {
            return ApplyResult.STALE_DROPPED;
        }
        states.put(key, command.animation());
        return ApplyResult.APPLIED;
    }

    /** Returns the most recently accepted semantic state for an entity in this exact connection epoch. */
    public Optional<SyncedAnimationState> entityState(int entityId) {
        if (connectionSession == null || entityId < 0) {
            return Optional.empty();
        }
        return Optional.ofNullable(states.get(new BlendInstanceKey.Entity(connectionSession, entityId)));
    }

    /** Returns the latest semantic state only when the caller names the current dimension explicitly. */
    public Optional<SyncedAnimationState> blockEntityState(BlendResourceId dimension, long packedBlockPos) {
        if (activeDimension == null || !activeDimension.equals(Objects.requireNonNull(dimension, "dimension"))) {
            return Optional.empty();
        }
        return Optional.ofNullable(states.get(new BlendInstanceKey.BlockEntity(dimension, packedBlockPos)));
    }

    /** Removes one full current-session entity target when it unloads, protecting against entity-id reuse. */
    public void onEntityUnload(int entityId) {
        String activeSession = connectionSession;
        if (activeSession != null && entityId >= 0) {
            states.remove(new BlendInstanceKey.Entity(activeSession, entityId));
        }
    }

    /** Removes one dimension-scoped block target when its block entity unloads. */
    public void onBlockEntityUnload(BlendResourceId dimension, long packedBlockPos) {
        if (activeDimension != null && activeDimension.equals(Objects.requireNonNull(dimension, "dimension"))) {
            states.remove(new BlendInstanceKey.BlockEntity(dimension, packedBlockPos));
        }
    }

    public Optional<String> connectionSession() {
        return Optional.ofNullable(connectionSession);
    }

    public Optional<BlendResourceId> activeDimension() {
        return Optional.ofNullable(activeDimension);
    }

    public int size() {
        return states.size();
    }

    private BlendInstanceKey toInstanceKey(ClientAnimationTarget target) {
        if (connectionSession == null || activeDimension == null) {
            return null;
        }
        if (target instanceof ClientAnimationTarget.EntityTarget entityTarget) {
            return new BlendInstanceKey.Entity(connectionSession, entityTarget.entityId());
        }
        ClientAnimationTarget.BlockEntityTarget blockEntityTarget = (ClientAnimationTarget.BlockEntityTarget) target;
        if (!activeDimension.equals(blockEntityTarget.dimension())) {
            return null;
        }
        return new BlendInstanceKey.BlockEntity(blockEntityTarget.dimension(), blockEntityTarget.packedBlockPos());
    }

    /** Outcome for queue and receiver diagnostics without exposing storage internals. */
    public enum ApplyResult {
        APPLIED,
        STALE_DROPPED,
        OUT_OF_SESSION_DROPPED
    }
}
