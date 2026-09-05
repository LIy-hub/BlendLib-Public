package com.liy.blendlib.fabric.client.host;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Objects;

/** Immutable configuration values and host-specific invariants for all X4 targets. */
public final class X4HostConfigurations {
    /** Default maximum extent accepted by formal hosts for a prepared non-missing model. */
    public static final float DEFAULT_MAXIMUM_BOUNDS_EXTENT = 64.0F;

    private X4HostConfigurations() {
    }

    /** Armor slot/layer and owner-identity configuration. */
    public record Armor(
            X4ArmorSlot slot,
            X4ArmorLayer layer,
            BlendResourceId ownerIdentity,
            float maximumBoundsExtent) implements X4HostConfiguration<X4HostFrames.Armor> {
        public Armor {
            slot = Objects.requireNonNull(slot, "slot");
            layer = Objects.requireNonNull(layer, "layer");
            ownerIdentity = Objects.requireNonNull(ownerIdentity, "ownerIdentity");
            requireBoundsLimit(maximumBoundsExtent);
        }

        @Override
        public X4HostKind hostKind() {
            return X4HostKind.ARMOR;
        }

        @Override
        public void validateFrame(X4HostSpec<X4HostFrames.Armor> specification, X4HostFrames.Armor frame) {
            requireScope(specification, frame.snapshotFrame(), ownerIdentity);
            if (frame.slot() != slot || frame.layer() != layer) {
                throw new IllegalArgumentException("Armor frame slot/layer must match its frozen host configuration");
            }
        }
    }

    /** Bounded projectile lifetime/orientation configuration. */
    public record Projectile(long maximumLifetimeTicks, float maximumBoundsExtent)
            implements X4HostConfiguration<X4HostFrames.Projectile> {
        public Projectile {
            if (maximumLifetimeTicks <= 0L || maximumLifetimeTicks > 72_000L) {
                throw new IllegalArgumentException("Projectile maximum lifetime must be in [1, 72000] ticks");
            }
            requireBoundsLimit(maximumBoundsExtent);
        }

        @Override
        public X4HostKind hostKind() {
            return X4HostKind.PROJECTILE;
        }

        @Override
        public void validateFrame(X4HostSpec<X4HostFrames.Projectile> specification, X4HostFrames.Projectile frame) {
            if (frame.ageTicks() > maximumLifetimeTicks) {
                throw new IllegalArgumentException("Projectile frame exceeds the configured presentation lifetime");
            }
            if (Math.abs(frame.yawDegrees()) > 360.0F || Math.abs(frame.pitchDegrees()) > 360.0F) {
                throw new IllegalArgumentException("Projectile presentation orientation must remain in [-360, 360] degrees");
            }
        }
    }

    /** Held-item hand/context configuration, intentionally separate from ordinary marker items. */
    public record HeldItem(X4RenderHand hand, X4HeldItemContext context, float maximumBoundsExtent)
            implements X4HostConfiguration<X4HostFrames.HeldItem> {
        public HeldItem {
            hand = Objects.requireNonNull(hand, "hand");
            context = Objects.requireNonNull(context, "context");
            requireBoundsLimit(maximumBoundsExtent);
        }

        @Override
        public X4HostKind hostKind() {
            return X4HostKind.HELD_ITEM;
        }

        @Override
        public void validateFrame(X4HostSpec<X4HostFrames.HeldItem> specification, X4HostFrames.HeldItem frame) {
            if (frame.hand() != hand || frame.context() != context) {
                throw new IllegalArgumentException("Held-item frame hand/context must match its frozen host configuration");
            }
        }
    }

    /** First-person hand isolation configuration. */
    public record FirstPersonHand(X4RenderHand hand, BlendResourceId viewSession, float maximumBoundsExtent)
            implements X4HostConfiguration<X4HostFrames.FirstPersonHand> {
        public FirstPersonHand {
            hand = Objects.requireNonNull(hand, "hand");
            viewSession = Objects.requireNonNull(viewSession, "viewSession");
            requireBoundsLimit(maximumBoundsExtent);
        }

        @Override
        public X4HostKind hostKind() {
            return X4HostKind.FIRST_PERSON_HAND;
        }

        @Override
        public void validateFrame(
                X4HostSpec<X4HostFrames.FirstPersonHand> specification,
                X4HostFrames.FirstPersonHand frame) {
            requireScope(specification, frame.snapshotFrame(), viewSession);
            if (frame.hand() != hand || !frame.viewSession().equals(viewSession)) {
                throw new IllegalArgumentException("First-person hand frame is not isolated to the configured hand/view session");
            }
        }
    }

    /** Bounded GUI preview transform and viewport configuration. */
    public record GuiPreview(
            int maximumWidth,
            int maximumHeight,
            float maximumAbsoluteTranslation,
            float minimumScale,
            float maximumScale,
            float maximumBoundsExtent) implements X4HostConfiguration<X4HostFrames.GuiPreview> {
        public GuiPreview {
            if (maximumWidth <= 0 || maximumWidth > 4096 || maximumHeight <= 0 || maximumHeight > 4096) {
                throw new IllegalArgumentException("GUI preview maximum viewport must be in [1, 4096]");
            }
            if (!Float.isFinite(maximumAbsoluteTranslation) || maximumAbsoluteTranslation < 0.0F
                    || maximumAbsoluteTranslation > 256.0F) {
                throw new IllegalArgumentException("GUI preview translation bound must be finite and in [0, 256]");
            }
            if (!Float.isFinite(minimumScale) || !Float.isFinite(maximumScale)
                    || minimumScale < X4Transform.MIN_SCALE || minimumScale > maximumScale
                    || maximumScale > X4Transform.MAX_SCALE) {
                throw new IllegalArgumentException("GUI preview scale range must stay within X4 transform bounds");
            }
            requireBoundsLimit(maximumBoundsExtent);
        }

        @Override
        public X4HostKind hostKind() {
            return X4HostKind.GUI_PREVIEW;
        }

        @Override
        public void validateFrame(X4HostSpec<X4HostFrames.GuiPreview> specification, X4HostFrames.GuiPreview frame) {
            if (frame.width() > maximumWidth || frame.height() > maximumHeight) {
                throw new IllegalArgumentException("GUI preview frame exceeds its configured viewport bound");
            }
            X4Transform transform = frame.snapshotFrame().transform();
            if (Math.abs(transform.translationX()) > maximumAbsoluteTranslation
                    || Math.abs(transform.translationY()) > maximumAbsoluteTranslation
                    || Math.abs(transform.translationZ()) > maximumAbsoluteTranslation
                    || transform.uniformScale() < minimumScale
                    || transform.uniformScale() > maximumScale) {
                throw new IllegalArgumentException("GUI preview transform exceeds its frozen bounded configuration");
            }
            // callerScissorEpoch is deliberately observed but never mutated by this lifecycle.
        }
    }

    /** Stable world-object identity configuration. */
    public record WorldObject(BlendResourceId worldId, float maximumBoundsExtent)
            implements X4HostConfiguration<X4HostFrames.WorldObject> {
        public WorldObject {
            worldId = Objects.requireNonNull(worldId, "worldId");
            requireBoundsLimit(maximumBoundsExtent);
        }

        @Override
        public X4HostKind hostKind() {
            return X4HostKind.WORLD_OBJECT;
        }

        @Override
        public void validateFrame(X4HostSpec<X4HostFrames.WorldObject> specification, X4HostFrames.WorldObject frame) {
            requireScope(specification, frame.snapshotFrame(), worldId);
            if (!frame.worldId().equals(worldId)) {
                throw new IllegalArgumentException("World-object frame cannot cross its configured world identity");
            }
        }
    }

    /** Persistent VFX owner/lifetime/global-budget configuration. */
    public record PersistentVfx(
            BlendResourceId ownerIdentity,
            long maximumLifetimeTicks,
            int maximumActiveInstances,
            float maximumBoundsExtent) implements X4HostConfiguration<X4HostFrames.PersistentVfx> {
        public PersistentVfx {
            ownerIdentity = Objects.requireNonNull(ownerIdentity, "ownerIdentity");
            if (maximumLifetimeTicks <= 0L || maximumLifetimeTicks > 72_000L) {
                throw new IllegalArgumentException("Persistent VFX lifetime must be in [1, 72000] ticks");
            }
            if (maximumActiveInstances <= 0 || maximumActiveInstances > 4096) {
                throw new IllegalArgumentException("Persistent VFX active-instance budget must be in [1, 4096]");
            }
            requireBoundsLimit(maximumBoundsExtent);
        }

        @Override
        public X4HostKind hostKind() {
            return X4HostKind.PERSISTENT_VFX;
        }

        @Override
        public void validateFrame(X4HostSpec<X4HostFrames.PersistentVfx> specification, X4HostFrames.PersistentVfx frame) {
            requireScope(specification, frame.snapshotFrame(), ownerIdentity);
            if (!frame.ownerId().equals(ownerIdentity)
                    || frame.ageTicks() > maximumLifetimeTicks
                    || frame.activeInstances() > maximumActiveInstances) {
                throw new IllegalArgumentException("Persistent VFX frame violates owner, lifetime, or budget configuration");
            }
        }
    }

    /** Explicit-opt-in Experimental player replacement configuration. */
    public record PlayerReplacement(
            X4ExperimentalAccess access,
            BlendResourceId playerSession,
            boolean localPlayerOnly,
            float maximumBoundsExtent) implements X4HostConfiguration<X4HostFrames.PlayerReplacement> {
        public PlayerReplacement {
            access = Objects.requireNonNull(access, "access");
            access.requireEnabled();
            playerSession = Objects.requireNonNull(playerSession, "playerSession");
            requireBoundsLimit(maximumBoundsExtent);
        }

        @Override
        public X4HostKind hostKind() {
            return X4HostKind.PLAYER_REPLACEMENT;
        }

        @Override
        public void validateFrame(
                X4HostSpec<X4HostFrames.PlayerReplacement> specification,
                X4HostFrames.PlayerReplacement frame) {
            requireScope(specification, frame.snapshotFrame(), playerSession);
            if (!frame.playerSession().equals(playerSession) || (localPlayerOnly && !frame.localPlayer())) {
                throw new IllegalArgumentException("Experimental player replacement frame violates its session/local-player boundary");
            }
        }
    }

    /** Explicit-opt-in Experimental mount composite configuration. */
    public record MountComposite(
            X4ExperimentalAccess access,
            X4CompositeGraph graph,
            X4HostIdentity root,
            float maximumBoundsExtent) implements X4HostConfiguration<X4HostFrames.MountComposite> {
        public MountComposite {
            access = Objects.requireNonNull(access, "access");
            access.requireEnabled();
            graph = Objects.requireNonNull(graph, "graph");
            root = Objects.requireNonNull(root, "root");
            graph.requireRootedScope(root);
            requireBoundsLimit(maximumBoundsExtent);
        }

        @Override
        public X4HostKind hostKind() {
            return X4HostKind.MOUNT_COMPOSITE_ENTITY;
        }

        @Override
        public void validateSpecificationIdentity(X4HostIdentity identity) {
            if (!root.equals(identity)) {
                throw new IllegalArgumentException("Experimental mount composite graph root must equal the host specification identity");
            }
            graph.requireRootedScope(root);
        }

        @Override
        public void validateFrame(X4HostSpec<X4HostFrames.MountComposite> specification, X4HostFrames.MountComposite frame) {
            if (!frame.graph().equals(graph) || !frame.root().equals(root)) {
                throw new IllegalArgumentException("Experimental mount composite frame must use its frozen acyclic graph/root");
            }
        }
    }

    /** Explicit-opt-in Experimental multi-entity composite configuration. */
    public record MultiEntityComposite(
            X4ExperimentalAccess access,
            X4CompositeGraph graph,
            X4HostIdentity root,
            float maximumBoundsExtent) implements X4HostConfiguration<X4HostFrames.MultiEntityComposite> {
        public MultiEntityComposite {
            access = Objects.requireNonNull(access, "access");
            access.requireEnabled();
            graph = Objects.requireNonNull(graph, "graph");
            root = Objects.requireNonNull(root, "root");
            graph.requireRootedScope(root);
            requireBoundsLimit(maximumBoundsExtent);
        }

        @Override
        public X4HostKind hostKind() {
            return X4HostKind.MULTI_ENTITY_COMPOSITE;
        }

        @Override
        public void validateSpecificationIdentity(X4HostIdentity identity) {
            if (!root.equals(identity)) {
                throw new IllegalArgumentException("Experimental multi-entity composite graph root must equal the host specification identity");
            }
            graph.requireRootedScope(root);
        }

        @Override
        public void validateFrame(
                X4HostSpec<X4HostFrames.MultiEntityComposite> specification,
                X4HostFrames.MultiEntityComposite frame) {
            if (!frame.graph().equals(graph) || !frame.root().equals(root)) {
                throw new IllegalArgumentException("Experimental multi-entity frame must use its frozen acyclic graph/root");
            }
        }
    }

    private static void requireScope(
            X4HostSpec<?> specification,
            X4SnapshotFrame frame,
            BlendResourceId expectedScope) {
        if (!specification.identity().equals(frame.identity()) || !frame.identity().scope().equals(expectedScope)) {
            throw new IllegalArgumentException("X4 host frame must retain the configured scoped identity");
        }
    }

    private static void requireBoundsLimit(float maximumBoundsExtent) {
        if (!Float.isFinite(maximumBoundsExtent) || maximumBoundsExtent <= 0.0F || maximumBoundsExtent > 1024.0F) {
            throw new IllegalArgumentException("X4 maximum bounds extent must be finite and in (0, 1024]");
        }
    }
}
