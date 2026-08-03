package com.liy.blendlib.spi.experimental;

/** Final immutable outcome for one capability request in a frozen plan. */
@ExperimentalBlendLibSpi
public enum CapabilitySelectionOutcome {
    /** A single compatible highest-priority provider offer was selected. */
    SELECTED,

    /** An optional request selected its predeclared semantic-equivalent fallback. */
    FALLBACK,

    /** No safe selection or declared fallback exists, so publication must fail closed. */
    FAILED
}
