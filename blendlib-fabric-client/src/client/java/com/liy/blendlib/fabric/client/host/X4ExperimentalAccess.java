package com.liy.blendlib.fabric.client.host;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Objects;

/** Explicit opt-in token required before an Experimental X4 target can be constructed. */
public final class X4ExperimentalAccess {
    private static final X4ExperimentalAccess DISABLED = new X4ExperimentalAccess(null, false);

    private final BlendResourceId acknowledgement;
    private final boolean enabled;

    private X4ExperimentalAccess(BlendResourceId acknowledgement, boolean enabled) {
        this.acknowledgement = acknowledgement;
        this.enabled = enabled;
    }

    /** Returns the default disabled capability. */
    public static X4ExperimentalAccess disabled() {
        return DISABLED;
    }

    /**
     * Creates an explicit opt-in token retained in an Experimental configuration for diagnostics.
     *
     * <p>The acknowledgement is a semantic resource identity only; it does not trigger provider
     * discovery, resource lookup, or a global bootstrap side effect.</p>
     */
    public static X4ExperimentalAccess optIn(BlendResourceId acknowledgement) {
        return new X4ExperimentalAccess(Objects.requireNonNull(acknowledgement, "acknowledgement"), true);
    }

    /** Whether this token authorizes Experimental construction. */
    public boolean enabled() {
        return enabled;
    }

    /** Optional diagnostic acknowledgement for an enabled token. */
    public BlendResourceId acknowledgement() {
        if (!enabled) {
            throw new IllegalStateException("The default X4 Experimental access token has no acknowledgement");
        }
        return acknowledgement;
    }

    void requireEnabled() {
        if (!enabled) {
            throw new IllegalStateException("This X4 host target is Experimental and requires explicit opt-in");
        }
    }
}
