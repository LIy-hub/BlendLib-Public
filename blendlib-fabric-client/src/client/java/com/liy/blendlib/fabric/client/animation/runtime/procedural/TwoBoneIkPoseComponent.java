package com.liy.blendlib.fabric.client.animation.runtime.procedural;

import com.liy.blendlib.core.animation.runtime.LocalPose;
import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import com.liy.blendlib.fabric.client.animation.runtime.ClientAnimationPoseContext;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Stateless analytic IK for three distinct, uniquely named direct parent-child nodes.
 * Target/pole are model-space positions, before entity/root/world transforms. Only the root
 * and middle local rotations change. Incoming translations/scales and the end rotation survive.
 *
 * <p>Strict v1 positive uniform scales (including ancestors) are supported. Non-uniform,
 * reflected and singular scales are not supported by {@link Transform}. Invalid rig/domain
 * configuration or numerical overflow throws before any pose is returned; callers permitting
 * resource packs to replace the chain must handle that configuration failure themselves.
 * A segment shorter than max(1e-7 model units, 1e-7 of the longer segment) preserves the input.
 *
 * <p>Distances are clamped inside [abs(a-b)+guard, a+b-guard], where guard is
 * min(min(a,b)*0.25, max(1e-7 model units, (a+b)*1e-6)). A target at the root uses the current first-segment
 * direction; a collinear pole uses a deterministic perpendicular. No mutable result or entity
 * state is retained. Wrap in {@link WeightedPoseComponent} for weights/masks; partial weights,
 * masks and subsequent modifiers need not preserve endpoint reach.
 */
public final class TwoBoneIkPoseComponent implements ProceduralPoseComponent {
    /** Full-weight solve outcome, before an enclosing weighted wrapper or later modifiers. */
    public enum Status { REACHED, CLAMPED_NEAR, CLAMPED_FAR, DEGENERATE_CHAIN }

    /** Immutable per-invocation diagnostics; a degenerate result reports the unchanged endpoint. */
    public record Result(Status status, Vec3 effectiveTargetModelSpace,
            boolean usedDirectionFallback, boolean usedPoleFallback) {
        public Result {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(effectiveTargetModelSpace, "effectiveTargetModelSpace");
        }
    }

    private final String rootName;
    private final String middleName;
    private final String endName;
    private final Function<ClientAnimationPoseContext, TwoBoneIkTarget> target;
    private final BiConsumer<ClientAnimationPoseContext, Result> diagnosticListener;

    public TwoBoneIkPoseComponent(String rootName, String middleName, String endName,
            Function<ClientAnimationPoseContext, TwoBoneIkTarget> target) {
        this(rootName, middleName, endName, target, (context, result) -> { });
    }

    /** The diagnosticListener runs once after a successful/degenerate solve; its exceptions propagate. */
    public TwoBoneIkPoseComponent(String rootName, String middleName, String endName,
            Function<ClientAnimationPoseContext, TwoBoneIkTarget> target,
            BiConsumer<ClientAnimationPoseContext, Result> diagnosticListener) {
        this.rootName = PoseRotationMath.nodeName(rootName);
        this.middleName = PoseRotationMath.nodeName(middleName);
        this.endName = PoseRotationMath.nodeName(endName);
        if (rootName.equals(middleName) || rootName.equals(endName) || middleName.equals(endName)) {
            throw new IllegalArgumentException("Two-bone IK requires three distinct node names");
        }
        this.target = Objects.requireNonNull(target, "target");
        this.diagnosticListener = Objects.requireNonNull(diagnosticListener, "diagnosticListener");
    }

    @Override
    public LocalPose modify(ClientAnimationPoseContext context, LocalPose basePose) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(basePose, "basePose");
        var rig = context.rig();
        if (!rig.nodeIndices().equals(basePose.transforms().keySet())) {
            throw new IllegalArgumentException("Two-bone IK requires the exact rig node set");
        }
        int root = rig.requireNodeIndex(rootName);
        int middle = rig.requireNodeIndex(middleName);
        int end = rig.requireNodeIndex(endName);
        if (rig.parentIndex(middle).orElse(-1) != root || rig.parentIndex(end).orElse(-1) != middle) {
            throw new IllegalArgumentException("Two-bone IK requires a direct root -> middle -> end chain");
        }
        // Both positions come from exactly one extraction-time snapshot, including degenerate chains.
        TwoBoneIkTarget snapshot = Objects.requireNonNull(target.apply(context), "IK target snapshot");
        final LocalPose result;
        final Result diagnostic;
        try {
            Transform rootModel = PoseRotationMath.global(basePose, rig, root);
            Transform middleModel = rootModel.compose(basePose.transform(middle));
            Transform endModel = middleModel.compose(basePose.transform(end));
            D3 rootPoint = D3.of(rootModel.translation());
            D3 first = D3.of(middleModel.translation()).subtract(rootPoint);
            D3 second = D3.of(endModel.translation()).subtract(D3.of(middleModel.translation()));
            double a = first.length();
            double b = second.length();
            double minimumSegment = Math.max(1e-7, Math.max(a, b) * 1e-7);
            if (a <= minimumSegment || b <= minimumSegment) {
                result = basePose;
                diagnostic = new Result(Status.DEGENERATE_CHAIN, endModel.translation(), false, false);
            } else {
                D3 requested = D3.of(snapshot.targetModelSpace()).subtract(rootPoint);
                double requestedDistance = requested.length();
                double guard = Math.min(Math.min(a, b) * 0.25, Math.max(1e-7, (a + b) * 1e-6));
                boolean directionFallback = requestedDistance <= guard;
                D3 direction = directionFallback ? first.unit() : requested.unit();
                double minimum = Math.abs(a - b) + guard;
                double maximum = Math.max(minimum, a + b - guard);
                double distance = Math.max(minimum, Math.min(maximum, requestedDistance));
                Status status = requestedDistance < minimum ? Status.CLAMPED_NEAR
                        : requestedDistance > maximum ? Status.CLAMPED_FAR : Status.REACHED;
                D3 pole = D3.of(snapshot.poleModelSpace()).subtract(rootPoint);
                D3 plane = pole.subtract(direction.multiply(pole.dot(direction)));
                boolean poleFallback = plane.length() <= Math.max(1e-7, pole.length() * 1e-7);
                D3 bend = poleFallback ? perpendicular(direction) : plane.unit();
                double cosine = Math.max(-1, Math.min(1,
                        (a * a + distance * distance - b * b) / (2 * a * distance)));
                D3 elbowOffset = direction.multiply(a * cosine)
                        .add(bend.multiply(a * Math.sqrt(Math.max(0, 1 - cosine * cosine))));
                D3 effectiveOffset = direction.multiply(distance);
                Quaternion rootDelta = between(first, elbowOffset);
                D3 secondAfterRoot = D3.of(rootDelta.rotate(second.unit().vector()));
                Quaternion middleDelta = between(secondAfterRoot, effectiveOffset.subtract(elbowOffset));
                var parent = rig.parentIndex(root);
                Quaternion parentRotation = parent.isPresent()
                        ? PoseRotationMath.global(basePose, rig, parent.getAsInt()).rotation() : Quaternion.IDENTITY;
                Quaternion rootAfter = rootDelta.multiply(rootModel.rotation());
                Quaternion rootLocal = PoseRotationMath.inverse(parentRotation).multiply(rootAfter);
                Quaternion middleAfter = middleDelta.multiply(rootDelta).multiply(middleModel.rotation());
                Quaternion middleLocal = PoseRotationMath.inverse(rootAfter).multiply(middleAfter);
                result = PoseRotationMath.replace(basePose, Map.of(root, rootLocal, middle, middleLocal));
                // Check the solved chain as well as the input: a new bend can overflow even when
                // the old chain and target were finite. Never publish such a partial solution.
                PoseRotationMath.global(result, rig, end);
                diagnostic = new Result(status, rootPoint.add(effectiveOffset).vector(), directionFallback, poleFallback);
            }
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Two-bone IK cannot represent this pose in finite model space: "
                    + failure.getMessage(), failure);
        }
        diagnosticListener.accept(context, diagnostic);
        return result;
    }

    private static D3 perpendicular(D3 direction) {
        return direction.cross(Math.abs(direction.y) < 0.9 ? new D3(0, 1, 0) : new D3(1, 0, 0)).unit();
    }

    // atan2 avoids the near-antiparallel cancellation of quaternion (cross, 1 + dot).
    private static Quaternion between(D3 from, D3 to) {
        D3 a = from.unit();
        D3 b = to.unit();
        D3 cross = a.cross(b);
        double sine = cross.length();
        double cosine = Math.max(-1, Math.min(1, a.dot(b)));
        if (sine < 1e-12 && cosine > 0) return Quaternion.IDENTITY;
        D3 axis = sine < 1e-12 ? perpendicular(a) : cross.multiply(1 / sine);
        double half = Math.atan2(sine, cosine) * 0.5;
        D3 xyz = axis.multiply(Math.sin(half));
        return new Quaternion((float) xyz.x, (float) xyz.y, (float) xyz.z, (float) Math.cos(half)).normalized();
    }

    /** Double intermediates avoid finite float positions overflowing during subtraction/dot. */
    private record D3(double x, double y, double z) {
        static D3 of(Vec3 v) { return new D3(v.x(), v.y(), v.z()); }
        D3 add(D3 v) { return new D3(x + v.x, y + v.y, z + v.z); }
        D3 subtract(D3 v) { return new D3(x - v.x, y - v.y, z - v.z); }
        D3 multiply(double v) { return new D3(x * v, y * v, z * v); }
        double dot(D3 v) { return x * v.x + y * v.y + z * v.z; }
        double length() { return Math.sqrt(dot(this)); }
        D3 cross(D3 v) { return new D3(y * v.z - z * v.y, z * v.x - x * v.z, x * v.y - y * v.x); }
        D3 unit() {
            double length = length();
            if (!Double.isFinite(length) || length <= 0) throw new IllegalArgumentException("invalid segment direction");
            return multiply(1 / length);
        }
        Vec3 vector() { return new Vec3((float) x, (float) y, (float) z); }
    }
}
