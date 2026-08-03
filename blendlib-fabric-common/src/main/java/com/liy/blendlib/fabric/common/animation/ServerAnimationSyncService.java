package com.liy.blendlib.fabric.common.animation;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.fabric.common.network.BlendLibAnimationPayloads;
import com.liy.blendlib.fabric.common.network.BlockEntityAnimationPayload;
import com.liy.blendlib.fabric.common.network.EntityAnimationPayload;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Fabric server adapter for semantic BlendLib animation commands.
 *
 * <p>This service owns no model or render state. It registers only clientbound payloads, broadcasts current
 * semantics to actual trackers, and derives block-entity tracking-start replay with an observer set because Fabric
 * 26.1.2 exposes no block-entity START_TRACKING event.</p>
 */
final class ServerAnimationSyncService {
    private final ServerAnimationStateRegistry states = new ServerAnimationStateRegistry();
    private final Map<BlockObserverKey, Set<UUID>> observedBlockTrackers = new HashMap<>();
    private boolean initialized;

    synchronized void initialize() {
        if (initialized) {
            return;
        }
        BlendLibAnimationPayloads.registerClientbound();
        EntityTrackingEvents.START_TRACKING.register(this::onEntityStartTracking);
        ServerTickEvents.END_LEVEL_TICK.register(this::onEndLevelTick);
        ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register(this::onBlockEntityUnload);
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> states.clearEntity(entity.getUUID()));
        ServerLevelEvents.UNLOAD.register((server, level) -> clearLevel(dimensionId(level)));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> clearAll());
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> forgetPlayer(handler.getPlayer().getUUID()));
        initialized = true;
    }

    SyncedAnimationState trigger(Entity entity, BlendAnimationKey animationKey, float speed, long seed) {
        ServerLevel level = requireServerEntity(entity);
        SyncedAnimationState state = states.triggerEntity(
                entity.getUUID(), animationKey, level.getGameTime(), speed, seed);
        sendEntityToTrackers(entity, state);
        return state;
    }

    SyncedAnimationState setPersistent(Entity entity, BlendAnimationKey animationKey, float speed, long seed) {
        ServerLevel level = requireServerEntity(entity);
        SyncedAnimationState state = states.setPersistentEntity(
                entity.getUUID(), animationKey, level.getGameTime(), speed, seed);
        sendEntityToTrackers(entity, state);
        return state;
    }

    SyncedAnimationState trigger(BlockEntity blockEntity, BlendAnimationKey animationKey, float speed, long seed) {
        ServerLevel level = requireServerBlockEntity(blockEntity);
        BlendResourceId dimension = dimensionId(level);
        SyncedAnimationState state = states.triggerBlockEntity(
                dimension, blockEntity.getBlockPos().asLong(), animationKey, level.getGameTime(), speed, seed);
        sendBlockEntityToTrackers(blockEntity, state, false);
        return state;
    }

    SyncedAnimationState setPersistent(BlockEntity blockEntity, BlendAnimationKey animationKey, float speed, long seed) {
        ServerLevel level = requireServerBlockEntity(blockEntity);
        BlendResourceId dimension = dimensionId(level);
        SyncedAnimationState state = states.setPersistentBlockEntity(
                dimension, blockEntity.getBlockPos().asLong(), animationKey, level.getGameTime(), speed, seed);
        sendBlockEntityToTrackers(blockEntity, state, true);
        return state;
    }

    ServerAnimationStateRegistry stateRegistry() {
        return states;
    }

    private void onEntityStartTracking(Entity entity, ServerPlayer player) {
        states.replayPersistentEntity(entity.getUUID())
                .ifPresent(state -> ServerPlayNetworking.send(player, new EntityAnimationPayload(entity.getId(), state)));
    }

    private void onEndLevelTick(ServerLevel level) {
        BlendResourceId dimension = dimensionId(level);
        for (ServerAnimationStateRegistry.PersistentBlockAnimation persistent : states.persistentBlocksIn(dimension)) {
            BlockPos blockPos = BlockPos.of(persistent.packedBlockPos());
            BlockEntity blockEntity = level.getBlockEntity(blockPos);
            BlockObserverKey observerKey = new BlockObserverKey(dimension, persistent.packedBlockPos());
            if (blockEntity == null || blockEntity.isRemoved()) {
                states.clearBlockEntity(dimension, persistent.packedBlockPos());
                observedBlockTrackers.remove(observerKey);
                continue;
            }

            Collection<ServerPlayer> trackers = PlayerLookup.tracking(blockEntity);
            Set<UUID> observed = observedBlockTrackers.computeIfAbsent(observerKey, ignored -> new HashSet<>());
            observed.retainAll(trackers.stream().map(ServerPlayer::getUUID).collect(java.util.stream.Collectors.toSet()));
            for (ServerPlayer tracker : trackers) {
                if (observed.add(tracker.getUUID())) {
                    states.replayPersistentBlockEntity(dimension, persistent.packedBlockPos())
                            .ifPresent(state -> ServerPlayNetworking.send(
                                    tracker, new BlockEntityAnimationPayload(blockPos, state)));
                }
            }
        }
    }

    private void onBlockEntityUnload(BlockEntity blockEntity, ServerLevel level) {
        BlendResourceId dimension = dimensionId(level);
        long packedBlockPos = blockEntity.getBlockPos().asLong();
        states.clearBlockEntity(dimension, packedBlockPos);
        observedBlockTrackers.remove(new BlockObserverKey(dimension, packedBlockPos));
    }

    private void forgetPlayer(UUID playerUuid) {
        for (Set<UUID> observed : observedBlockTrackers.values()) {
            observed.remove(playerUuid);
        }
        observedBlockTrackers.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    private void clearLevel(BlendResourceId dimension) {
        states.clearDimension(dimension);
        observedBlockTrackers.keySet().removeIf(key -> key.dimension.equals(dimension));
    }

    private void clearAll() {
        states.clearAll();
        observedBlockTrackers.clear();
    }

    private void sendEntityToTrackers(Entity entity, SyncedAnimationState state) {
        EntityAnimationPayload payload = new EntityAnimationPayload(entity.getId(), state);
        for (ServerPlayer tracker : PlayerLookup.tracking(entity)) {
            ServerPlayNetworking.send(tracker, payload);
        }
    }

    private void sendBlockEntityToTrackers(BlockEntity blockEntity, SyncedAnimationState state, boolean persistent) {
        BlockEntityAnimationPayload payload = new BlockEntityAnimationPayload(blockEntity.getBlockPos(), state);
        BlendResourceId dimension = dimensionId(requireServerBlockEntity(blockEntity));
        BlockObserverKey observerKey = new BlockObserverKey(dimension, blockEntity.getBlockPos().asLong());
        Set<UUID> observed = persistent
                ? observedBlockTrackers.computeIfAbsent(observerKey, ignored -> new HashSet<>())
                : null;
        for (ServerPlayer tracker : PlayerLookup.tracking(blockEntity)) {
            ServerPlayNetworking.send(tracker, payload);
            if (observed != null) {
                observed.add(tracker.getUUID());
            }
        }
    }

    private static ServerLevel requireServerEntity(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        if (entity.isRemoved() || !(entity.level() instanceof ServerLevel serverLevel)) {
            throw new IllegalArgumentException("BlendLib animation commands require a live server-side entity");
        }
        return serverLevel;
    }

    private static ServerLevel requireServerBlockEntity(BlockEntity blockEntity) {
        Objects.requireNonNull(blockEntity, "blockEntity");
        if (blockEntity.isRemoved() || !(blockEntity.getLevel() instanceof ServerLevel serverLevel)) {
            throw new IllegalArgumentException("BlendLib animation commands require a live server-side block entity");
        }
        return serverLevel;
    }

    private static BlendResourceId dimensionId(ServerLevel level) {
        return BlendResourceId.parse(level.dimension().identifier().toString());
    }

    private record BlockObserverKey(BlendResourceId dimension, long packedBlockPos) {
        private BlockObserverKey {
            dimension = Objects.requireNonNull(dimension, "dimension");
        }
    }
}
