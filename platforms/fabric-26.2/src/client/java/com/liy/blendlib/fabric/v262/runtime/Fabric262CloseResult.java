package com.liy.blendlib.fabric.v262.runtime;

/**
 * Terminal outcome of an exact Minecraft 26.2 Fabric runtime close request.
 *
 * <p><strong>Platform target:</strong> Fabric on Minecraft 26.2. A result distinguishes the first
 * exact close from an idempotent repeat without accepting a receipt from another installation.</p>
 */
public enum Fabric262CloseResult {
    /** The exact active installation closed and retired its current resource generation. */
    CLOSED,

    /** The exact receipt was already closed; no additional lifecycle action occurred. */
    ALREADY_CLOSED
}
