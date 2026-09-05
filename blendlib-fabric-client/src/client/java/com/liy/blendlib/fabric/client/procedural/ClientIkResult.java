package com.liy.blendlib.fabric.client.procedural;

import com.liy.blendlib.core.model.Vec3;
import com.liy.blendlib.core.procedural.ProceduralDirective;
import com.liy.blendlib.core.procedural.ProceduralOperation;
import java.util.List;
import java.util.Objects;

/** Immutable mathematical result whose only effectful output is an exact two-directive rotation contract. */
public final class ClientIkResult {
    private final ClientIkStatus status;
    private final int iterations;
    private final Vec3 effectiveTargetModelSpace;
    private final Vec3 elbowModelSpace;
    private final List<ProceduralDirective> directives;

    private ClientIkResult(
            ClientIkStatus status,
            int iterations,
            Vec3 effectiveTargetModelSpace,
            Vec3 elbowModelSpace,
            List<ProceduralDirective> directives) {
        this.status = status;
        this.iterations = iterations;
        this.effectiveTargetModelSpace = effectiveTargetModelSpace;
        this.elbowModelSpace = elbowModelSpace;
        this.directives = directives;
    }

    static ClientIkResult solved(
            ClientIkStatus status,
            Vec3 effectiveTargetModelSpace,
            Vec3 elbowModelSpace,
            int rootBone,
            int middleBone,
            List<ProceduralDirective> directives) {
        status = Objects.requireNonNull(status, "status");
        if (status == ClientIkStatus.REJECTED) {
            throw new IllegalArgumentException("a rejected IK result must not carry directives");
        }
        effectiveTargetModelSpace = Objects.requireNonNull(effectiveTargetModelSpace, "effectiveTargetModelSpace");
        elbowModelSpace = Objects.requireNonNull(elbowModelSpace, "elbowModelSpace");
        directives = List.copyOf(Objects.requireNonNull(directives, "directives"));
        if (directives.size() != 2
                || !(directives.get(0).operation() instanceof ProceduralOperation.RotationOffset rootRotation)
                || !(directives.get(1).operation() instanceof ProceduralOperation.RotationOffset middleRotation)
                || rootRotation.boneIndex() != rootBone || middleRotation.boneIndex() != middleBone) {
            throw new IllegalArgumentException("a solved two-bone IK result must contain exactly root then middle rotation offsets");
        }
        return new ClientIkResult(status, 1, effectiveTargetModelSpace, elbowModelSpace, directives);
    }

    static ClientIkResult rejected(Vec3 rootModelSpace) {
        rootModelSpace = Objects.requireNonNull(rootModelSpace, "rootModelSpace");
        return new ClientIkResult(ClientIkStatus.REJECTED, 0, rootModelSpace, rootModelSpace, List.of());
    }

    public ClientIkStatus status() {
        return status;
    }

    public int iterations() {
        return iterations;
    }

    public Vec3 effectiveTargetModelSpace() {
        return effectiveTargetModelSpace;
    }

    public Vec3 elbowModelSpace() {
        return elbowModelSpace;
    }

    public List<ProceduralDirective> directives() {
        return directives;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ClientIkResult result)) {
            return false;
        }
        return iterations == result.iterations && status == result.status
                && effectiveTargetModelSpace.equals(result.effectiveTargetModelSpace)
                && elbowModelSpace.equals(result.elbowModelSpace) && directives.equals(result.directives);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, iterations, effectiveTargetModelSpace, elbowModelSpace, directives);
    }

    @Override
    public String toString() {
        return "ClientIkResult[status=" + status + ", iterations=" + iterations + ", effectiveTargetModelSpace="
                + effectiveTargetModelSpace + ", elbowModelSpace=" + elbowModelSpace + ", directives=" + directives + ']';
    }
}
