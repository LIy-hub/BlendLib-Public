package com.liy.blendlib.core.animation.v2;

import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable complete local pose in one already-frozen {@link BoneSchema} order. */
public final class AnimationV2Pose {
    private final Transform[] transforms;

    public AnimationV2Pose(List<Transform> transforms) {
        Objects.requireNonNull(transforms, "transforms");
        if (transforms.isEmpty() || transforms.size() > AnimationV2Limits.MAX_BONES_PER_SCHEMA) {
            throw new IllegalArgumentException("pose transform count is outside supported bounds");
        }
        this.transforms = new Transform[transforms.size()];
        for (int index = 0; index < transforms.size(); index++) {
            this.transforms[index] = Objects.requireNonNull(transforms.get(index), "transform");
        }
    }

    private AnimationV2Pose(Transform[] transforms, boolean trusted) {
        this.transforms = trusted ? transforms : transforms.clone();
    }

    /** Transfers a freshly allocated owner-only output array into one immutable pose snapshot. */
    static AnimationV2Pose takeOwnership(Transform[] transforms) {
        Objects.requireNonNull(transforms, "transforms");
        if (transforms.length == 0 || transforms.length > AnimationV2Limits.MAX_BONES_PER_SCHEMA) {
            throw new IllegalArgumentException("pose transform count is outside supported bounds");
        }
        for (Transform transform : transforms) {
            Objects.requireNonNull(transform, "transform");
        }
        return new AnimationV2Pose(transforms, true);
    }

    public int boneCount() {
        return transforms.length;
    }

    public Transform transform(int index) {
        if (index < 0 || index >= transforms.length) {
            throw new IllegalArgumentException("bone index is outside pose bounds: " + index);
        }
        return transforms[index];
    }

    public List<Transform> transforms() {
        List<Transform> copy = new ArrayList<>(transforms.length);
        for (Transform transform : transforms) {
            copy.add(transform);
        }
        return List.copyOf(copy);
    }

    static AnimationV2Pose blend(AnimationV2Pose left, AnimationV2Pose right, double amount) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        requireSameSize(left, right);
        if (!Double.isFinite(amount) || amount < 0.0D || amount > 1.0D) {
            throw new IllegalArgumentException("blend amount must be finite and in [0, 1]");
        }
        float factor = (float) amount;
        Transform[] result = new Transform[left.transforms.length];
        for (int index = 0; index < result.length; index++) {
            result[index] = interpolate(left.transforms[index], right.transforms[index], factor);
        }
        return takeOwnership(result);
    }

    static Transform interpolate(Transform left, Transform right, float amount) {
        AnimationV2TransformScratch scratch = new AnimationV2TransformScratch();
        scratch.setInterpolated(left, right, amount);
        return scratch.toTransform();
    }

    static Transform additive(Transform base, Transform rest, Transform sample, float weight) {
        if (!Float.isFinite(weight) || weight < 0.0F || weight > 1.0F) {
            throw new IllegalArgumentException("additive weight must be finite and in [0, 1]");
        }
        Vec3 translation = new Vec3(
                finite(base.translation().x() + (sample.translation().x() - rest.translation().x()) * weight),
                finite(base.translation().y() + (sample.translation().y() - rest.translation().y()) * weight),
                finite(base.translation().z() + (sample.translation().z() - rest.translation().z()) * weight));
        float restScale = rest.scale().x();
        float sampleScale = sample.scale().x();
        float baseScale = base.scale().x();
        float scale = finite(baseScale * (1.0F + (sampleScale / restScale - 1.0F) * weight));
        Quaternion inverseRest = new Quaternion(
                -rest.rotation().x(), -rest.rotation().y(), -rest.rotation().z(), rest.rotation().w());
        Quaternion delta = inverseRest.multiply(sample.rotation());
        Quaternion weightedDelta = Quaternion.slerp(Quaternion.IDENTITY, delta, weight);
        return new Transform(translation, base.rotation().multiply(weightedDelta), new Vec3(scale, scale, scale));
    }

    static Transform weightedOverride(Transform rest, List<WeightedTransform> values) {
        Objects.requireNonNull(rest, "rest");
        Objects.requireNonNull(values, "values");
        double sum = 0.0D;
        for (WeightedTransform value : values) {
            sum += value.weight();
        }
        double normalizer = Math.max(1.0D, sum);
        double restWeight = Math.max(0.0D, 1.0D - sum);
        double tx = rest.translation().x() * restWeight;
        double ty = rest.translation().y() * restWeight;
        double tz = rest.translation().z() * restWeight;
        double scale = rest.scale().x() * restWeight;
        Quaternion restRotation = rest.rotation();
        Quaternion reference = restWeight > 0.0D || values.isEmpty()
                ? restRotation
                : values.getFirst().transform().rotation();
        float referenceSign = AnimationV2TransformScratch.canonicalHemisphereSign(
                reference.x(), reference.y(), reference.z(), reference.w());
        float referenceX = reference.x() * referenceSign;
        float referenceY = reference.y() * referenceSign;
        float referenceZ = reference.z() * referenceSign;
        float referenceW = reference.w() * referenceSign;
        float restSign = AnimationV2TransformScratch.referenceHemisphereSign(
                referenceX, referenceY, referenceZ, referenceW,
                restRotation.x(), restRotation.y(), restRotation.z(), restRotation.w());
        double qx = restRotation.x() * restSign * restWeight;
        double qy = restRotation.y() * restSign * restWeight;
        double qz = restRotation.z() * restSign * restWeight;
        double qw = restRotation.w() * restSign * restWeight;
        for (WeightedTransform value : values) {
            double weight = value.weight() / normalizer;
            Transform transform = value.transform();
            tx += transform.translation().x() * weight;
            ty += transform.translation().y() * weight;
            tz += transform.translation().z() * weight;
            scale += transform.scale().x() * weight;
            Quaternion rotation = transform.rotation();
            float sign = AnimationV2TransformScratch.referenceHemisphereSign(
                    referenceX, referenceY, referenceZ, referenceW,
                    rotation.x(), rotation.y(), rotation.z(), rotation.w());
            qx += rotation.x() * sign * weight;
            qy += rotation.y() * sign * weight;
            qz += rotation.z() * sign * weight;
            qw += rotation.w() * sign * weight;
        }
        double quaternionLength = qx * qx + qy * qy + qz * qz + qw * qw;
        if (!Double.isFinite(quaternionLength) || quaternionLength <= 1.0E-12D) {
            qx = referenceX;
            qy = referenceY;
            qz = referenceZ;
            qw = referenceW;
        } else {
            double inverse = 1.0D / Math.sqrt(quaternionLength);
            qx *= inverse;
            qy *= inverse;
            qz *= inverse;
            qw *= inverse;
        }
        float outputSign = AnimationV2TransformScratch.canonicalHemisphereSign(
                finite(qx), finite(qy), finite(qz), finite(qw));
        qx *= outputSign;
        qy *= outputSign;
        qz *= outputSign;
        qw *= outputSign;
        return new Transform(new Vec3(finite(tx), finite(ty), finite(tz)),
                new Quaternion(finite(qx), finite(qy), finite(qz), finite(qw)),
                new Vec3(finite(scale), finite(scale), finite(scale)));
    }

    private static Vec3 lerp(Vec3 left, Vec3 right, float amount) {
        return new Vec3(
                finite(left.x() + (right.x() - left.x()) * amount),
                finite(left.y() + (right.y() - left.y()) * amount),
                finite(left.z() + (right.z() - left.z()) * amount));
    }

    private static float finite(double value) {
        float result = (float) value;
        if (!Double.isFinite(value) || !Float.isFinite(result)) {
            throw new IllegalArgumentException("v2 pose operation produced a non-finite value");
        }
        return result;
    }

    private static void requireSameSize(AnimationV2Pose left, AnimationV2Pose right) {
        if (left.transforms.length != right.transforms.length) {
            throw new IllegalArgumentException("poses must have identical bone counts");
        }
    }

    record WeightedTransform(Transform transform, float weight) {
        WeightedTransform {
            transform = Objects.requireNonNull(transform, "transform");
            if (!Float.isFinite(weight) || weight <= 0.0F || weight > 1.0F) {
                throw new IllegalArgumentException("override weight must be finite and in (0, 1]");
            }
        }
    }
}
