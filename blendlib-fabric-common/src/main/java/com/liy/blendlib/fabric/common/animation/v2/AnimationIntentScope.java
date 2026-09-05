package com.liy.blendlib.fabric.common.animation.v2;

import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.api.BlendResourceId;
import java.util.Comparator;
import java.util.Objects;

/** Immutable ownership fence for one client-visible animation instance generation. */
public record AnimationIntentScope(
        String sessionId,
        BlendResourceId worldId,
        BlendInstanceKey instanceKey,
        long generation) {
    public static final int MAX_SESSION_ID_UTF16_CODE_UNITS = 256;

    /** A stable total order used only for deterministic semantic reconciliation. */
    public static final Comparator<AnimationIntentScope> CANONICAL_ORDER = Comparator
            .comparing(AnimationIntentScope::sessionId)
            .thenComparing(scope -> scope.worldId().value())
            .thenComparing(AnimationIntentScope::instanceKey, AnimationIntentScope::compareInstanceKey)
            .thenComparingLong(AnimationIntentScope::generation);

    public AnimationIntentScope {
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        if (sessionId.isBlank() || sessionId.length() > MAX_SESSION_ID_UTF16_CODE_UNITS) {
            throw new IllegalArgumentException("sessionId must be non-blank and bounded");
        }
        worldId = Objects.requireNonNull(worldId, "worldId");
        instanceKey = Objects.requireNonNull(instanceKey, "instanceKey");
        if (generation < 0L) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
    }

    /** Returns whether only the frozen resource generation changed. */
    public boolean sameSessionWorldInstance(AnimationIntentScope other) {
        Objects.requireNonNull(other, "other");
        return sessionId.equals(other.sessionId)
                && worldId.equals(other.worldId)
                && instanceKey.equals(other.instanceKey);
    }

    private static int compareInstanceKey(BlendInstanceKey left, BlendInstanceKey right) {
        int kind = Integer.compare(instanceKind(left), instanceKind(right));
        if (kind != 0) {
            return kind;
        }
        return switch (left) {
            case BlendInstanceKey.Entity entity -> {
                BlendInstanceKey.Entity other = (BlendInstanceKey.Entity) right;
                int session = entity.connectionSession().compareTo(other.connectionSession());
                yield session != 0 ? session : Integer.compare(entity.entityId(), other.entityId());
            }
            case BlendInstanceKey.BlockEntity blockEntity -> {
                BlendInstanceKey.BlockEntity other = (BlendInstanceKey.BlockEntity) right;
                int dimension = blockEntity.dimension().value().compareTo(other.dimension().value());
                yield dimension != 0 ? dimension : Long.compare(blockEntity.packedBlockPos(), other.packedBlockPos());
            }
            case BlendInstanceKey.Item ignored -> 0;
            case BlendInstanceKey.Ephemeral ephemeral -> {
                BlendInstanceKey.Ephemeral other = (BlendInstanceKey.Ephemeral) right;
                int session = ephemeral.sessionId().compareTo(other.sessionId());
                yield session != 0 ? session : ephemeral.localId().compareTo(other.localId());
            }
        };
    }

    private static int instanceKind(BlendInstanceKey key) {
        return switch (key) {
            case BlendInstanceKey.Entity ignored -> 0;
            case BlendInstanceKey.BlockEntity ignored -> 1;
            case BlendInstanceKey.Item ignored -> 2;
            case BlendInstanceKey.Ephemeral ignored -> 3;
        };
    }
}
