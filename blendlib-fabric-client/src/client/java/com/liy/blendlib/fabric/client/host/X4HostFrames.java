package com.liy.blendlib.fabric.client.host;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Objects;

/** Strongly typed extraction frames for the formal and Experimental X4 host targets. */
public final class X4HostFrames {
    private X4HostFrames() {
    }

    /** Armor frame with explicit owner-facing slot/layer semantics. */
    public record Armor(X4SnapshotFrame snapshotFrame, X4ArmorSlot slot, X4ArmorLayer layer) implements X4HostFrame {
        public Armor {
            snapshotFrame = Objects.requireNonNull(snapshotFrame, "snapshotFrame");
            slot = Objects.requireNonNull(slot, "slot");
            layer = Objects.requireNonNull(layer, "layer");
        }
    }

    /** Projectile frame with bounded lifetime and finite presentation orientation. */
    public record Projectile(X4SnapshotFrame snapshotFrame, long ageTicks, float yawDegrees, float pitchDegrees)
            implements X4HostFrame {
        public Projectile {
            snapshotFrame = Objects.requireNonNull(snapshotFrame, "snapshotFrame");
            if (ageTicks < 0L || !Float.isFinite(yawDegrees) || !Float.isFinite(pitchDegrees)) {
                throw new IllegalArgumentException("Projectile lifetime and orientation must be finite and non-negative");
            }
        }
    }

    /** Held-item frame with a non-marker hand/context pair. */
    public record HeldItem(X4SnapshotFrame snapshotFrame, X4RenderHand hand, X4HeldItemContext context)
            implements X4HostFrame {
        public HeldItem {
            snapshotFrame = Objects.requireNonNull(snapshotFrame, "snapshotFrame");
            hand = Objects.requireNonNull(hand, "hand");
            context = Objects.requireNonNull(context, "context");
        }
    }

    /** First-person hand frame isolated by a view/session identity. */
    public record FirstPersonHand(X4SnapshotFrame snapshotFrame, X4RenderHand hand, BlendResourceId viewSession)
            implements X4HostFrame {
        public FirstPersonHand {
            snapshotFrame = Objects.requireNonNull(snapshotFrame, "snapshotFrame");
            hand = Objects.requireNonNull(hand, "hand");
            viewSession = Objects.requireNonNull(viewSession, "viewSession");
        }
    }

    /** GUI preview frame; the scissor epoch is observed only and never modified by the X4 adapter. */
    public record GuiPreview(X4SnapshotFrame snapshotFrame, int width, int height, long callerScissorEpoch)
            implements X4HostFrame {
        public GuiPreview {
            snapshotFrame = Objects.requireNonNull(snapshotFrame, "snapshotFrame");
            if (width <= 0 || height <= 0 || callerScissorEpoch < 0L) {
                throw new IllegalArgumentException("GUI preview dimensions must be positive and scissor epoch non-negative");
            }
        }
    }

    /** Stable world-object frame scoped to one declared world/dimension identity. */
    public record WorldObject(X4SnapshotFrame snapshotFrame, BlendResourceId worldId, long revision)
            implements X4HostFrame {
        public WorldObject {
            snapshotFrame = Objects.requireNonNull(snapshotFrame, "snapshotFrame");
            worldId = Objects.requireNonNull(worldId, "worldId");
            if (revision < 0L) {
                throw new IllegalArgumentException("World-object revision must be non-negative");
            }
        }
    }

    /** Persistent presentation-only VFX frame with owner, age, and global-budget observation. */
    public record PersistentVfx(X4SnapshotFrame snapshotFrame, BlendResourceId ownerId, long ageTicks, int activeInstances)
            implements X4HostFrame {
        public PersistentVfx {
            snapshotFrame = Objects.requireNonNull(snapshotFrame, "snapshotFrame");
            ownerId = Objects.requireNonNull(ownerId, "ownerId");
            if (ageTicks < 0L || activeInstances < 0) {
                throw new IllegalArgumentException("Persistent VFX age and active instance count must be non-negative");
            }
        }
    }

    /** Experimental player-replacement frame, constrained to an explicit player session. */
    public record PlayerReplacement(X4SnapshotFrame snapshotFrame, BlendResourceId playerSession, boolean localPlayer)
            implements X4HostFrame {
        public PlayerReplacement {
            snapshotFrame = Objects.requireNonNull(snapshotFrame, "snapshotFrame");
            playerSession = Objects.requireNonNull(playerSession, "playerSession");
        }
    }

    /** Experimental mount composite frame with a validated finite acyclic graph. */
    public record MountComposite(X4SnapshotFrame snapshotFrame, X4CompositeGraph graph, X4HostIdentity root)
            implements X4HostFrame {
        public MountComposite {
            snapshotFrame = Objects.requireNonNull(snapshotFrame, "snapshotFrame");
            graph = Objects.requireNonNull(graph, "graph");
            root = Objects.requireNonNull(root, "root");
        }
    }

    /** Experimental multi-entity composite frame with a validated finite acyclic graph. */
    public record MultiEntityComposite(X4SnapshotFrame snapshotFrame, X4CompositeGraph graph, X4HostIdentity root)
            implements X4HostFrame {
        public MultiEntityComposite {
            snapshotFrame = Objects.requireNonNull(snapshotFrame, "snapshotFrame");
            graph = Objects.requireNonNull(graph, "graph");
            root = Objects.requireNonNull(root, "root");
        }
    }
}
