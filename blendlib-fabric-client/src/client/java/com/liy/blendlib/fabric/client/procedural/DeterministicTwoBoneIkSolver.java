package com.liy.blendlib.fabric.client.procedural;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import com.liy.blendlib.core.procedural.ProceduralDirective;
import com.liy.blendlib.core.procedural.ProceduralOperation;
import java.util.List;
import java.util.Objects;

/**
 * One-pass analytical two-bone IK. It clamps unreachable targets and emits only local rotation-offset directives.
 */
public final class DeterministicTwoBoneIkSolver implements ExperimentalClientIkSolver {
    private static final float EPSILON = 1.0E-5F;
    private final BlendResourceId id;

    public DeterministicTwoBoneIkSolver(BlendResourceId id) {
        this.id = Objects.requireNonNull(id, "id");
        if (id.value().length() > 256) {
            throw new IllegalArgumentException("IK solver id exceeds X3 identity budget");
        }
    }

    @Override
    public BlendResourceId id() {
        return id;
    }

    @Override
    public ClientIkResult solve(ClientIkRequest request) {
        Objects.requireNonNull(request, "request");
        ClientIkRigSnapshot rig = request.rig();
        if (!rig.isDirectTwoBoneChain(request.rootBone(), request.middleBone(), request.endBone())) {
            return rejected(request);
        }
        Transform rootTransform = rig.modelTransform(request.rootBone());
        Transform middleTransform = rig.modelTransform(request.middleBone());
        Transform endTransform = rig.modelTransform(request.endBone());
        Vec3 root = rootTransform.translation();
        Vec3 middle = middleTransform.translation();
        Vec3 end = endTransform.translation();
        Vec3 firstSegment = middle.subtract(root);
        Vec3 secondSegment = end.subtract(middle);
        float firstLength = firstSegment.length();
        float secondLength = secondSegment.length();
        if (!finite(firstLength) || !finite(secondLength) || firstLength <= EPSILON || secondLength <= EPSILON) {
            return rejected(request);
        }

        Vec3 requestedDelta = request.targetModelSpace().subtract(root);
        float requestedDistance = requestedDelta.length();
        ClientIkStatus status = ClientIkStatus.REACHED;
        Vec3 direction;
        if (!finite(requestedDistance) || requestedDistance <= EPSILON) {
            direction = normalize(firstSegment);
            requestedDistance = Math.abs(firstLength - secondLength) + EPSILON;
            status = ClientIkStatus.DEGENERATE;
        } else {
            direction = normalize(requestedDelta);
        }
        float minimum = Math.abs(firstLength - secondLength) + EPSILON;
        float maximum = Math.max(minimum, firstLength + secondLength - EPSILON);
        float distance = requestedDistance;
        if (distance > maximum) {
            distance = maximum;
            status = ClientIkStatus.CLAMPED_FAR;
        } else if (distance < minimum) {
            distance = minimum;
            status = ClientIkStatus.CLAMPED_NEAR;
        }
        Vec3 poleOffset = request.poleModelSpace().subtract(root);
        Vec3 polePlane = subtract(poleOffset, multiply(direction, dot(poleOffset, direction)));
        if (polePlane.length() <= EPSILON) {
            polePlane = deterministicPerpendicular(direction);
            if (status == ClientIkStatus.REACHED) {
                status = ClientIkStatus.DEGENERATE;
            }
        }
        Vec3 bend = normalize(polePlane);
        float cosine = clamp((firstLength * firstLength + distance * distance - secondLength * secondLength)
                / (2.0F * firstLength * distance), -1.0F, 1.0F);
        float sine = (float) Math.sqrt(Math.max(0.0F, 1.0F - cosine * cosine));
        Vec3 elbow = add(root, add(multiply(direction, firstLength * cosine), multiply(bend, firstLength * sine)));
        Vec3 effectiveTarget = add(root, multiply(direction, distance));

        Quaternion rootWorldDelta = fromTo(normalize(firstSegment), normalize(elbow.subtract(root)));
        // The root directive is applied first. Calculate the middle correction from the segment after that root
        // rotation, otherwise the parent rotation would be applied twice when X3 composes the hierarchy.
        Vec3 secondAfterRoot = rootWorldDelta.rotate(normalize(secondSegment));
        Quaternion middleWorldDelta = fromTo(secondAfterRoot, normalize(effectiveTarget.subtract(elbow)));
        Quaternion rootLocalDelta = localPreRotation(rig.parentModelTransform(request.rootBone()).rotation(), rootWorldDelta);
        Quaternion middleParentAfterRoot = rootWorldDelta.multiply(rig.parentModelTransform(request.middleBone()).rotation());
        Quaternion middleLocalDelta = localPreRotation(middleParentAfterRoot, middleWorldDelta);
        List<ProceduralDirective> directives = List.of(
                new ProceduralDirective(id, request.priority(),
                        new ProceduralOperation.RotationOffset(request.rootBone(), rootLocalDelta)),
                new ProceduralDirective(id, request.priority(),
                        new ProceduralOperation.RotationOffset(request.middleBone(), middleLocalDelta)));
        return ClientIkResult.solved(status, effectiveTarget, elbow, request.rootBone(), request.middleBone(), directives);
    }

    private ClientIkResult rejected(ClientIkRequest request) {
        return ClientIkResult.rejected(request.rig().rootTranslationOrZero(request.rootBone()));
    }

    private static Quaternion localPreRotation(Quaternion parentRotation, Quaternion worldDelta) {
        Quaternion inverseParent = new Quaternion(-parentRotation.x(), -parentRotation.y(), -parentRotation.z(), parentRotation.w());
        return inverseParent.multiply(worldDelta).multiply(parentRotation);
    }

    private static Quaternion fromTo(Vec3 from, Vec3 to) {
        float cosine = clamp(dot(from, to), -1.0F, 1.0F);
        if (cosine > 1.0F - EPSILON) {
            return Quaternion.IDENTITY;
        }
        if (cosine < -1.0F + EPSILON) {
            Vec3 axis = deterministicPerpendicular(from);
            return new Quaternion(axis.x(), axis.y(), axis.z(), 0.0F);
        }
        Vec3 cross = from.cross(to);
        return new Quaternion(cross.x(), cross.y(), cross.z(), 1.0F + cosine).normalized();
    }

    private static Vec3 deterministicPerpendicular(Vec3 direction) {
        Vec3 basis = Math.abs(direction.y()) < 0.9F ? new Vec3(0.0F, 1.0F, 0.0F) : new Vec3(1.0F, 0.0F, 0.0F);
        return normalize(direction.cross(basis));
    }

    private static Vec3 normalize(Vec3 value) {
        return value.normalized();
    }

    private static Vec3 add(Vec3 left, Vec3 right) {
        return new Vec3(left.x() + right.x(), left.y() + right.y(), left.z() + right.z());
    }

    private static Vec3 subtract(Vec3 left, Vec3 right) {
        return new Vec3(left.x() - right.x(), left.y() - right.y(), left.z() - right.z());
    }

    private static Vec3 multiply(Vec3 value, float scalar) {
        return new Vec3(value.x() * scalar, value.y() * scalar, value.z() * scalar);
    }

    private static float dot(Vec3 left, Vec3 right) {
        return left.x() * right.x() + left.y() * right.y() + left.z() * right.z();
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static boolean finite(float value) {
        return Float.isFinite(value);
    }
}
