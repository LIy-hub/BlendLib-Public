package com.liy.blendlib.api;

import java.util.Objects;

/**
 * Typed, pure identity for one client-side BlendLib animation instance.
 *
 * <p>Variants intentionally cannot compare equal across domains. In
 * particular, an entity identity is scoped to a connection session so a bare
 * entity id cannot survive a reconnect or world change.</p>
 */
public sealed interface BlendInstanceKey permits BlendInstanceKey.Entity, BlendInstanceKey.BlockEntity,
        BlendInstanceKey.Item, BlendInstanceKey.Ephemeral {

    /**
     * Creates a connection-scoped entity instance identity.
     *
     * @param connectionSession non-blank connection/session token
     * @param entityId non-negative entity id within that session
     * @return typed entity identity
     */
    static Entity entity(String connectionSession, int entityId) {
        return new Entity(connectionSession, entityId);
    }

    /**
     * Creates a dimension-scoped block-entity instance identity.
     *
     * @param dimension canonical dimension identity
     * @param packedBlockPos platform-neutral packed block position
     * @return typed block-entity identity
     */
    static BlockEntity blockEntity(BlendResourceId dimension, long packedBlockPos) {
        return new BlockEntity(dimension, packedBlockPos);
    }

    /**
     * Returns the sole v1 item identity: item animation is stateless and loop-only.
     *
     * @return stateless item identity
     */
    static Item item() {
        return Item.STATELESS;
    }

    /**
     * Creates a caller-scoped ephemeral effect identity.
     *
     * @param sessionId non-blank local session token
     * @param localId non-blank caller-local id
     * @return typed ephemeral identity
     */
    static Ephemeral ephemeral(String sessionId, String localId) {
        return new Ephemeral(sessionId, localId);
    }

    /**
     * Entity identity scoped to the client connection that observed it.
     *
     * @param connectionSession non-blank connection/session token
     * @param entityId non-negative entity id within that session
     */
    record Entity(String connectionSession, int entityId) implements BlendInstanceKey {
        /**
         * Validates an entity identity in its connection-session scope.
         *
         * @param connectionSession non-blank connection/session token
         * @param entityId non-negative entity id
         */
        public Entity {
            connectionSession = requireToken(connectionSession, "connectionSession");
            if (entityId < 0) {
                throw new IllegalArgumentException("entityId must be non-negative");
            }
        }
    }

    /**
     * Block-entity identity using a pure dimension resource id and packed block position.
     *
     * @param dimension canonical dimension identity
     * @param packedBlockPos platform-neutral packed position
     */
    record BlockEntity(BlendResourceId dimension, long packedBlockPos) implements BlendInstanceKey {
        /**
         * Validates a block-entity identity in its dimension scope.
         *
         * @param dimension non-null canonical dimension identity
         * @param packedBlockPos platform-neutral packed position
         */
        public BlockEntity {
            dimension = Objects.requireNonNull(dimension, "dimension");
        }
    }

    /** v1 items have no persistent stack identity or transient animation state. */
    enum Item implements BlendInstanceKey {
        /** The only stateless item identity supported by the v1 semantic boundary. */
        STATELESS
    }

    /**
     * Caller-supplied identity for effects that exist only within one local session.
     *
     * @param sessionId non-blank caller session id
     * @param localId non-blank local effect id
     */
    record Ephemeral(String sessionId, String localId) implements BlendInstanceKey {
        /**
         * Validates caller-supplied identifiers for one local session.
         *
         * @param sessionId non-blank caller session id
         * @param localId non-blank local effect id
         */
        public Ephemeral {
            sessionId = requireToken(sessionId, "sessionId");
            localId = requireToken(localId, "localId");
        }
    }

    private static String requireToken(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
