package com.liy.blendlib.datagen;

/**
 * Requirement strength for an emitted experimental capability declaration sidecar.
 *
 * <p><strong>Experimental boundary:</strong> this is authoring metadata. A runtime must still use
 * the frozen capability protocol to negotiate and fail closed; generated data alone grants nothing.</p>
 */
public enum DatagenCapabilityRequirement {
    /** The capability must be selected by a compatible provider; absence is a fail-closed outcome. */
    REQUIRED,

    /** The capability may be absent only when a named semantic fallback is declared. */
    OPTIONAL
}
