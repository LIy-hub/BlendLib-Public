package com.liy.blendlib.neoforge.v262;

/**
 * Explicit status of the independent Minecraft 26.2 NeoForge adapter binding.
 *
 * <p><strong>WAITING boundary:</strong> this state prevents a pure Java bridge artifact from being
 * mistaken for a loader-bound NeoForge runtime. It changes only after an owner verifies official
 * 26.2 coordinates, mappings, event lifecycle, and public client rendering/resource APIs.</p>
 */
public enum NeoForge262BindingState {
    /** Pure resource/host bridge is available, but no NeoForge loader binding is represented. */
    WAITING_OFFICIAL_26_2_BINDING,

    /** A later verified target can replace the template with an actual independently versioned adapter. */
    READY_FOR_VERIFIED_BINDING
}
