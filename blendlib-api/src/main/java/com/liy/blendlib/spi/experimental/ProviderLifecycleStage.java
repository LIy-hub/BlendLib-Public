package com.liy.blendlib.spi.experimental;

/** Ordered lifecycle stages available to a selected controlled provider. */
@ExperimentalBlendLibSpi
public enum ProviderLifecycleStage {
    /** Provider metadata is registered but has not been discovered. */
    REGISTER,

    /** Metadata-only capability discovery is occurring. */
    DISCOVER,

    /** A capability plan is frozen and immutable. */
    FREEZE,

    /** Background resource preparation may occur. */
    PREPARE,

    /** Adapter-bound resource application may occur. */
    APPLY,

    /** The generation is atomically visible to consumers. */
    PUBLISH,

    /** The generation accepts no new bindings and waits for pins to drain. */
    RETIRE,

    /** Provider-owned resources are released exactly once. */
    CLOSE
}
