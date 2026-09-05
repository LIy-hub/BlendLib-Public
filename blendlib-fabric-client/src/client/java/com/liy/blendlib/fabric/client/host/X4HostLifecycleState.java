package com.liy.blendlib.fabric.client.host;

/** Lifecycle state of one immutable X4 client-host adapter. */
public enum X4HostLifecycleState {
    /** The adapter was built from immutable configuration but has not been frozen. */
    CONFIGURING,

    /** Configuration is frozen and the adapter may extract generation-pinned snapshots. */
    FROZEN,

    /** At least one caller-owned snapshot lease is active for one exact resource generation. */
    PREPARED,

    /**
     * Retirement rejects new prepare/submit admission while open snapshots remain future release
     * sources and only submit holds admitted before the terminal epoch may finish.
     */
    RETIRING,

    /** Retirement has drained every X4 lease and released its exact generation pins. */
    RETIRED,

    /**
     * Shutdown rejects new prepare/submit admission while open snapshots remain future release
     * sources and only submit holds admitted before the terminal epoch may finish.
     */
    CLOSING,

    /** The adapter has released every X4-owned generation pin and will not accept further work. */
    CLOSED
}
