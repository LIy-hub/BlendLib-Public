package com.liy.blendlib.spi.experimental;

/** Observable state of one generation-scoped controlled provider session. */
@ExperimentalBlendLibSpi
public enum ProviderLifecycleState {
    /** Plan is frozen, but no provider preparation has run. */
    FROZEN,

    /** All selected providers completed preparation. */
    PREPARED,

    /** All selected providers completed application. */
    APPLIED,

    /** The generation may issue immutable snapshot pins. */
    PUBLISHED,

    /** Retirement was requested and the session waits for any snapshot pins to drain. */
    RETIRING,

    /** Preparation or application failed; publication is prohibited. */
    FAILED,

    /** This session released ownership; the final shared owner performs the one terminal close attempt. */
    CLOSED
}
