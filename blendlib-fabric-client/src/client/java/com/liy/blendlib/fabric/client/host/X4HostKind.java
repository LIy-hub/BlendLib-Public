package com.liy.blendlib.fabric.client.host;

/**
 * Client-target-specific host categories introduced by X4.
 *
 * <p>These values deliberately live in the 26.1.2 client adapter instead of the stable X1
 * {@code HostKind}. They therefore do not change the three stable semantic X1 registration
 * categories or make an Experimental target part of the pure API.</p>
 */
public enum X4HostKind {
    /** Armor worn by an identified client-side owner. */
    ARMOR(false),

    /** A bounded-lifetime projectile presentation. */
    PROJECTILE(false),

    /** A held-item presentation, distinct from the ordinary marker-item model adapter. */
    HELD_ITEM(false),

    /** An isolated first-person hand presentation. */
    FIRST_PERSON_HAND(false),

    /** A bounded GUI-only model preview. */
    GUI_PREVIEW(false),

    /** A stable world-object presentation. */
    WORLD_OBJECT(false),

    /** A bounded, presentation-only persistent visual effect. */
    PERSISTENT_VFX(false),

    /** Experimental replacement of the local player presentation. */
    PLAYER_REPLACEMENT(true),

    /** Experimental mount/composite entity presentation. */
    MOUNT_COMPOSITE_ENTITY(true),

    /** Experimental composition spanning multiple presentation entities. */
    MULTI_ENTITY_COMPOSITE(true);

    private final boolean experimental;

    X4HostKind(boolean experimental) {
        this.experimental = experimental;
    }

    /** Whether this target requires an explicit Experimental opt-in. */
    public boolean experimental() {
        return experimental;
    }

    /** Whether this target is part of X4's formal host set. */
    public boolean formal() {
        return !experimental;
    }
}
