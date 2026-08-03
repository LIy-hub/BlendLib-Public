package com.liy.blendlib.fabric.client.animation;

import com.liy.blendlib.api.BlendInstanceKey;

import java.util.Optional;
import java.util.UUID;

/**
 * Client-lifecycle owner for the single extraction-side animation registry.
 *
 * <p>The Fabric adapter invokes {@link #onWorldDisconnect()} when the active
 * play session ends. This type intentionally contains no platform event import
 * so registry mutation remains client-extraction-only and independently testable.</p>
 */
public final class ClientAnimationLifecycleBridge {
    private final ClientAnimationInstanceRegistry registry;
    private String currentConnectionSession;

    public ClientAnimationLifecycleBridge(int poseCacheCapacity) {
        registry = new ClientAnimationInstanceRegistry(poseCacheCapacity);
    }

    /** Returns the one registry owned by this client lifecycle. */
    public ClientAnimationInstanceRegistry registry() {
        return registry;
    }

    /**
     * Starts a new adapter-private client connection epoch.
     *
     * <p>The token exists only for the active play connection and is never derived from,
     * or persisted as, an entity id. A repeated init defensively retires state from the
     * preceding connection before rotating the token.</p>
     */
    public void onPlayInit() {
        if (currentConnectionSession != null) {
            registry.onWorldDisconnect();
        }
        currentConnectionSession = UUID.randomUUID().toString();
    }

    /**
     * Creates the typed key that client entity bindings for the current play connection use.
     */
    public BlendInstanceKey.Entity entityKey(int entityId) {
        return activeEntityKey(entityId)
                .orElseThrow(() -> new IllegalStateException("no active client play connection"));
    }

    /**
     * Resolves an entity key only while the client still owns an active play epoch.
     *
     * <p>Entity-unload and render-extraction callbacks can arrive after disconnect cleanup has
     * retired the epoch. Those teardown paths must not recreate an identity from a bare entity id;
     * callers receive an empty result and can retire or skip their local work instead.</p>
     */
    public Optional<BlendInstanceKey.Entity> activeEntityKey(int entityId) {
        if (currentConnectionSession == null) {
            return Optional.empty();
        }
        return Optional.of(new BlendInstanceKey.Entity(currentConnectionSession, entityId));
    }

    /** Clears all per-world controller, instance, pose, and cache-observation state. */
    public void onWorldDisconnect() {
        registry.onWorldDisconnect();
        currentConnectionSession = null;
    }

    /**
     * Retires only typed entity instances correlated with a client entity-unload callback.
     *
     * <p>The callback id is combined with the active adapter-private connection token,
     * then exact-match removed as a full typed entity key. It is never retained as a
     * standalone identity.</p>
     *
     * @return number of removed typed entity instances
     */
    public int onEntityUnload(int entityId) {
        return activeEntityKey(entityId)
                .map(registry::removeUnloadedEntity)
                .orElse(0);
    }

    /**
     * Retires one typed block-entity instance correlated with a client chunk-unload callback.
     *
     * @return number of removed typed block-entity instances
     */
    public int onBlockEntityUnload(BlendInstanceKey.BlockEntity key) {
        return registry.removeUnloadedBlockEntity(key);
    }
}
