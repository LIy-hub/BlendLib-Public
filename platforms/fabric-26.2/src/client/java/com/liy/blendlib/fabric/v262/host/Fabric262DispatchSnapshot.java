package com.liy.blendlib.fabric.v262.host;

import com.liy.blendlib.fabric.v262.diagnostic.Fabric262Diagnostic;
import com.liy.blendlib.fabric.v262.model.Fabric262PoseSnapshot;
import com.liy.blendlib.fabric.v262.model.Fabric262RenderSnapshot;
import java.util.Objects;

/** Exact dispatcher-owned lease for one generation-pinned Fabric 26.2 render snapshot. */
final class Fabric262DispatchSnapshot implements AutoCloseable {
    private final Fabric262HostRenderDispatcher dispatcher;
    private final Fabric262RenderSnapshot snapshot;
    private final Fabric262PoseSnapshot pose;
    private final Fabric262Diagnostic animationDiagnostic;
    private boolean closed;

    Fabric262DispatchSnapshot(
            Fabric262HostRenderDispatcher dispatcher,
            Fabric262RenderSnapshot snapshot,
            Fabric262PoseSnapshot pose,
            Fabric262Diagnostic animationDiagnostic) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.pose = Objects.requireNonNull(pose, "pose");
        this.animationDiagnostic = animationDiagnostic;
    }

    synchronized Fabric262RenderSnapshot snapshot() {
        if (closed) {
            throw new IllegalStateException("A closed Fabric 26.2 dispatch snapshot cannot be submitted");
        }
        return snapshot;
    }

    synchronized Fabric262PoseSnapshot pose() {
        if (closed) {
            throw new IllegalStateException("A closed Fabric 26.2 dispatch snapshot has no usable pose");
        }
        return pose;
    }

    /** Returns a bounded extraction diagnostic when submission is using strict rest-pose fallback. */
    Fabric262Diagnostic animationDiagnostic() {
        return animationDiagnostic;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        // A failed generation release leaves this wrapper open so its exact dispatcher owner can
        // retain and retry the raw snapshot rather than losing a pin behind a terminal CAS.
        dispatcher.release(snapshot);
        closed = true;
    }
}
