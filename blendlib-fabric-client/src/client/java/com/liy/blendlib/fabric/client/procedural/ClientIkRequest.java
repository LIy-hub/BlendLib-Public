package com.liy.blendlib.fabric.client.procedural;

import com.liy.blendlib.core.model.Vec3;
import java.util.Objects;

/** Immutable request whose transforms and hierarchy come only from {@link ClientIkRigSnapshot}. */
public final class ClientIkRequest {
    private final ClientIkRigSnapshot rig;
    private final int rootBone;
    private final int middleBone;
    private final int endBone;
    private final Vec3 targetModelSpace;
    private final Vec3 poleModelSpace;
    private final int priority;

    private ClientIkRequest(
            ClientIkRigSnapshot rig,
            int rootBone,
            int middleBone,
            int endBone,
            Vec3 targetModelSpace,
            Vec3 poleModelSpace,
            int priority) {
        this.rig = Objects.requireNonNull(rig, "rig");
        if (rootBone < 0 || middleBone < 0 || endBone < 0 || rootBone == middleBone || rootBone == endBone
                || middleBone == endBone) {
            throw new IllegalArgumentException("IK chain requires three distinct non-negative bone indices");
        }
        this.rootBone = rootBone;
        this.middleBone = middleBone;
        this.endBone = endBone;
        this.targetModelSpace = bounded(Objects.requireNonNull(targetModelSpace, "targetModelSpace"), "targetModelSpace");
        this.poleModelSpace = bounded(Objects.requireNonNull(poleModelSpace, "poleModelSpace"), "poleModelSpace");
        this.priority = priority;
    }

    public static ClientIkRequest forRig(
            ClientIkRigSnapshot rig,
            int rootBone,
            int middleBone,
            int endBone,
            Vec3 targetModelSpace,
            Vec3 poleModelSpace,
            int priority) {
        return new ClientIkRequest(rig, rootBone, middleBone, endBone, targetModelSpace, poleModelSpace, priority);
    }

    public ClientIkRigSnapshot rig() {
        return rig;
    }

    public int rootBone() {
        return rootBone;
    }

    public int middleBone() {
        return middleBone;
    }

    public int endBone() {
        return endBone;
    }

    public Vec3 targetModelSpace() {
        return targetModelSpace;
    }

    public Vec3 poleModelSpace() {
        return poleModelSpace;
    }

    public int priority() {
        return priority;
    }

    private static Vec3 bounded(Vec3 value, String name) {
        if (Math.abs(value.x()) > 16_384.0F || Math.abs(value.y()) > 16_384.0F || Math.abs(value.z()) > 16_384.0F) {
            throw new IllegalArgumentException(name + " exceeds client IK bounds");
        }
        return value;
    }
}
