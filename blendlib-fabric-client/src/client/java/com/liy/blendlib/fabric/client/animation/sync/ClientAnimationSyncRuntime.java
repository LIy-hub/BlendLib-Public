package com.liy.blendlib.fabric.client.animation.sync;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.fabric.common.animation.SyncedAnimationState;
import com.liy.blendlib.fabric.common.network.BlockEntityAnimationPayload;
import com.liy.blendlib.fabric.common.network.EntityAnimationPayload;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

/**
 * Client-thread bridge between Fabric payload reception and the renderer-independent semantic sync store.
 *
 * <p>Network handlers schedule into this runtime on the client executor. This type then only queries target
 * availability, maintains a bounded TTL queue, and exposes the latest accepted semantics; it performs no resource
 * loading, JSON/GLB parsing, rendering, or controller mutation.</p>
 */
public final class ClientAnimationSyncRuntime {
    private static final System.Logger LOGGER = System.getLogger("BlendLib");

    private final ClientAnimationSyncStore store;
    private final UnknownTargetQueue unknownTargets;
    private String connectionSession;
    private BlendResourceId activeDimension;
    private long clientTick;

    public ClientAnimationSyncRuntime() {
        this(new ClientAnimationSyncStore(), new UnknownTargetQueue());
    }

    ClientAnimationSyncRuntime(ClientAnimationSyncStore store, UnknownTargetQueue unknownTargets) {
        this.store = Objects.requireNonNull(store, "store");
        this.unknownTargets = Objects.requireNonNull(unknownTargets, "unknownTargets");
    }

    /** Resets connection-local state; the first subsequent level observation establishes a fresh session epoch. */
    public void onPlayInit() {
        connectionSession = null;
        activeDimension = null;
        clientTick = 0L;
        unknownTargets.clear();
        store.disconnect();
    }

    /** Clears all semantic and pending state after the client disconnects. */
    public void onDisconnect() {
        onPlayInit();
    }

    /** Advances TTL processing and detects every loaded-level/dimension epoch transition. */
    public void onClientEndTick(Minecraft client) {
        Objects.requireNonNull(client, "client");
        if (client.level == null) {
            return;
        }
        clientTick = Math.incrementExact(clientTick);
        ClientLevel level = client.level;
        ensureActiveLevel(level);
        UnknownTargetQueue.RetryResult retry = unknownTargets.retry(clientTick, target -> isAvailable(level, target));
        for (ClientAnimationCommand command : retry.readyCommands()) {
            store.apply(command);
        }
        if (retry.expiredCommands() > 0) {
            LOGGER.log(System.Logger.Level.DEBUG, "Discarded {0} BlendLib animation packet(s) after unknown-target TTL", retry.expiredCommands());
        }
    }

    /** Receives a clientbound entity command only after the network callback has entered the client executor. */
    public void receive(EntityAnimationPayload payload, ClientLevel level) {
        Objects.requireNonNull(payload, "payload");
        if (level == null) {
            return;
        }
        ensureActiveLevel(level);
        ClientAnimationCommand command = new ClientAnimationCommand(
                new ClientAnimationTarget.EntityTarget(payload.entityId()), payload.animation());
        if (level.getEntity(payload.entityId()) == null) {
            unknownTargets.enqueue(command, clientTick);
            return;
        }
        store.apply(command);
    }

    /** Receives a clientbound block-entity command scoped to the level currently carried by the connection. */
    public void receive(BlockEntityAnimationPayload payload, ClientLevel level) {
        Objects.requireNonNull(payload, "payload");
        if (level == null) {
            return;
        }
        BlendResourceId dimension = ensureActiveLevel(level);
        ClientAnimationCommand command = new ClientAnimationCommand(
                new ClientAnimationTarget.BlockEntityTarget(dimension, payload.blockPos().asLong()), payload.animation());
        if (level.getBlockEntity(payload.blockPos()) == null) {
            unknownTargets.enqueue(command, clientTick);
            return;
        }
        store.apply(command);
    }

    /**
     * Clears both applied and pending entity state before a network entity id can be reused.
     *
     * <p>The unload callback supplies its level dimension so a late old-dimension callback cannot erase an entity
     * already accepted in a newly active dimension epoch.</p>
     */
    public void onEntityUnload(BlendResourceId dimension, int entityId) {
        Objects.requireNonNull(dimension, "dimension");
        if (store.activeDimension().filter(dimension::equals).isEmpty()) {
            return;
        }
        store.onEntityUnload(entityId);
        unknownTargets.remove(new ClientAnimationTarget.EntityTarget(entityId));
    }

    /** Clears both applied and pending state for an unloaded block entity in its exact client dimension. */
    public void onBlockEntityUnload(BlendResourceId dimension, BlockPos blockPos) {
        Objects.requireNonNull(blockPos, "blockPos");
        ClientAnimationTarget.BlockEntityTarget target =
                new ClientAnimationTarget.BlockEntityTarget(Objects.requireNonNull(dimension, "dimension"), blockPos.asLong());
        store.onBlockEntityUnload(dimension, blockPos.asLong());
        unknownTargets.remove(target);
    }

    /** Latest accepted entity semantics for the active connection epoch. */
    public Optional<SyncedAnimationState> entityState(int entityId) {
        return store.entityState(entityId);
    }

    /** Latest accepted block-entity semantics for an explicitly named current dimension. */
    public Optional<SyncedAnimationState> blockEntityState(BlendResourceId dimension, BlockPos blockPos) {
        return store.blockEntityState(Objects.requireNonNull(dimension, "dimension"),
                Objects.requireNonNull(blockPos, "blockPos").asLong());
    }

    /** Exposes the renderer-independent store for future P6 adapters without exposing a network packet object. */
    public ClientAnimationSyncStore store() {
        return store;
    }

    public int pendingUnknownTargetCount() {
        return unknownTargets.size();
    }

    private BlendResourceId ensureActiveLevel(ClientLevel level) {
        BlendResourceId dimension = BlendResourceId.parse(level.dimension().identifier().toString());
        if (connectionSession == null || !dimension.equals(activeDimension)) {
            connectionSession = UUID.randomUUID().toString();
            activeDimension = dimension;
            unknownTargets.clear();
            store.beginSession(connectionSession, dimension);
        }
        return dimension;
    }

    private static boolean isAvailable(ClientLevel level, ClientAnimationTarget target) {
        if (target instanceof ClientAnimationTarget.EntityTarget entityTarget) {
            return level.getEntity(entityTarget.entityId()) != null;
        }
        ClientAnimationTarget.BlockEntityTarget blockEntityTarget = (ClientAnimationTarget.BlockEntityTarget) target;
        BlendResourceId levelDimension = BlendResourceId.parse(level.dimension().identifier().toString());
        return levelDimension.equals(blockEntityTarget.dimension())
                && level.getBlockEntity(BlockPos.of(blockEntityTarget.packedBlockPos())) != null;
    }
}
