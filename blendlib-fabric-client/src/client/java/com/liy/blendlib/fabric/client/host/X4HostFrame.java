package com.liy.blendlib.fabric.client.host;

/** Base interface implemented by strongly typed host extraction inputs. */
public interface X4HostFrame {
    /** Shared immutable render data captured before submit. */
    X4SnapshotFrame snapshotFrame();
}
