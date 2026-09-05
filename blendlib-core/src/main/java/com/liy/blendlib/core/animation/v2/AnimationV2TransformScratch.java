package com.liy.blendlib.core.animation.v2;

import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;

/** Owner-thread mutable transform components used to avoid per-layer pose allocation during evaluation. */
final class AnimationV2TransformScratch {
    private static final double QUATERNION_EPSILON = 1.0E-12D;
    private static final float HEMISPHERE_EPSILON = 1.0E-6F;

    float tx;
    float ty;
    float tz;
    float qx;
    float qy;
    float qz;
    float qw;
    float scale;

    void set(Transform transform) {
        tx = transform.translation().x();
        ty = transform.translation().y();
        tz = transform.translation().z();
        qx = transform.rotation().x();
        qy = transform.rotation().y();
        qz = transform.rotation().z();
        qw = transform.rotation().w();
        scale = transform.scale().x();
    }

    void set(AnimationV2TransformScratch other) {
        tx = other.tx;
        ty = other.ty;
        tz = other.tz;
        qx = other.qx;
        qy = other.qy;
        qz = other.qz;
        qw = other.qw;
        scale = other.scale;
    }

    void setInterpolated(Transform left, Transform right, double amount) {
        setInterpolated(
                left.translation().x(), left.translation().y(), left.translation().z(),
                left.rotation().x(), left.rotation().y(), left.rotation().z(), left.rotation().w(), left.scale().x(),
                right.translation().x(), right.translation().y(), right.translation().z(),
                right.rotation().x(), right.rotation().y(), right.rotation().z(), right.rotation().w(), right.scale().x(),
                amount);
    }

    void setInterpolated(AnimationV2TransformScratch left, AnimationV2TransformScratch right, double amount) {
        setInterpolated(
                left.tx, left.ty, left.tz, left.qx, left.qy, left.qz, left.qw, left.scale,
                right.tx, right.ty, right.tz, right.qx, right.qy, right.qz, right.qw, right.scale,
                amount);
    }

    void applyAdditive(
            AnimationV2TransformScratch rest,
            AnimationV2TransformScratch sample,
            double weight) {
        if (weight == 0.0D) {
            return;
        }
        // Do not form sample - rest first: at weight one, a MAX rest and MIN sample would round that delta back to
        // MAX before current and rest can cancel. Three independent double terms preserve both the endpoint and an
        // override base: current + sample * weight - rest * weight.
        tx = finite(sumThree((double) tx, (double) sample.tx * weight, -(double) rest.tx * weight));
        ty = finite(sumThree((double) ty, (double) sample.ty * weight, -(double) rest.ty * weight));
        tz = finite(sumThree((double) tz, (double) sample.tz * weight, -(double) rest.tz * weight));
        if (weight == 1.0D) {
            // The endpoint is an exact relative-scale application. With no override base this is exactly sample;
            // with one it remains base * sample / rest rather than incorrectly replacing the base with sample.
            scale = positiveFinite(((double) scale * (double) sample.scale) / (double) rest.scale);
        } else {
            scale = positiveFinite((double) scale * ((1.0D - weight)
                    + weight * ((double) sample.scale / (double) rest.scale)));
        }

        applyAdditiveRotation(rest, sample, weight);
    }

    /**
     * Applies {@code current * slerp(identity, inverse(rest) * sample, weight)} without publishing an intermediate
     * float quaternion. The direct endpoint only applies when current and rest describe the same canonical group
     * rotation; an override or prior additive contribution with a different rotation keeps the general product.
     */
    private void applyAdditiveRotation(
            AnimationV2TransformScratch rest,
            AnimationV2TransformScratch sample,
            double weight) {
        if (weight == 1.0D && hasSameCanonicalGroupRotation(rest)) {
            setCanonicalRotationFrom(sample);
            return;
        }

        double inverseX = -(double) rest.qx;
        double inverseY = -(double) rest.qy;
        double inverseZ = -(double) rest.qz;
        double inverseW = (double) rest.qw;
        // Keep the relative delta in primitive doubles through both Hamilton products. A float publication here loses
        // legal MIN_VALUE components before current * delta gets its exact group-identity cancellation opportunity.
        double deltaX = sumFour(inverseW * (double) sample.qx, inverseX * (double) sample.qw,
                inverseY * (double) sample.qz, -inverseZ * (double) sample.qy);
        double deltaY = sumFour(inverseW * (double) sample.qy, -inverseX * (double) sample.qz,
                inverseY * (double) sample.qw, inverseZ * (double) sample.qx);
        double deltaZ = sumFour(inverseW * (double) sample.qz, inverseX * (double) sample.qy,
                -inverseY * (double) sample.qx, inverseZ * (double) sample.qw);
        double deltaW = sumFour(inverseW * (double) sample.qw, -inverseX * (double) sample.qx,
                -inverseY * (double) sample.qy, -inverseZ * (double) sample.qz);

        double deltaLengthSquared = sumFour(deltaX * deltaX, deltaY * deltaY, deltaZ * deltaZ, deltaW * deltaW);
        if (!Double.isFinite(deltaLengthSquared) || deltaLengthSquared <= QUATERNION_EPSILON) {
            deltaX = 0.0D;
            deltaY = 0.0D;
            deltaZ = 0.0D;
            deltaW = 1.0D;
        } else {
            double inverseLength = 1.0D / Math.sqrt(deltaLengthSquared);
            deltaX *= inverseLength;
            deltaY *= inverseLength;
            deltaZ *= inverseLength;
            deltaW *= inverseLength;
        }

        double deltaSign = canonicalHemisphereSign(deltaX, deltaY, deltaZ, deltaW);
        deltaX *= deltaSign;
        deltaY *= deltaSign;
        deltaZ *= deltaSign;
        deltaW *= deltaSign;

        double weightedX;
        double weightedY;
        double weightedZ;
        double weightedW;
        if (weight == 1.0D) {
            weightedX = deltaX;
            weightedY = deltaY;
            weightedZ = deltaZ;
            weightedW = deltaW;
        } else {
            double cosine = deltaW;
            if (cosine < 0.0D) {
                deltaX = -deltaX;
                deltaY = -deltaY;
                deltaZ = -deltaZ;
                deltaW = -deltaW;
                cosine = -cosine;
            }
            if (cosine > 0.9995D) {
                weightedX = weight * deltaX;
                weightedY = weight * deltaY;
                weightedZ = weight * deltaZ;
                weightedW = (1.0D - weight) + weight * deltaW;
            } else {
                double theta = Math.acos(Math.min(1.0D, Math.max(-1.0D, cosine)));
                double sine = Math.sin(theta);
                double leftWeight = Math.sin((1.0D - weight) * theta) / sine;
                double rightWeight = Math.sin(weight * theta) / sine;
                weightedX = rightWeight * deltaX;
                weightedY = rightWeight * deltaY;
                weightedZ = rightWeight * deltaZ;
                weightedW = leftWeight + rightWeight * deltaW;
            }
        }

        double weightedLengthSquared = sumFour(
                weightedX * weightedX, weightedY * weightedY, weightedZ * weightedZ, weightedW * weightedW);
        if (!Double.isFinite(weightedLengthSquared) || weightedLengthSquared <= QUATERNION_EPSILON) {
            weightedX = 0.0D;
            weightedY = 0.0D;
            weightedZ = 0.0D;
            weightedW = 1.0D;
        } else {
            double inverseLength = 1.0D / Math.sqrt(weightedLengthSquared);
            weightedX *= inverseLength;
            weightedY *= inverseLength;
            weightedZ *= inverseLength;
            weightedW *= inverseLength;
        }

        double nextX = sumFour((double) qw * weightedX, (double) qx * weightedW,
                (double) qy * weightedZ, -(double) qz * weightedY);
        double nextY = sumFour((double) qw * weightedY, -(double) qx * weightedZ,
                (double) qy * weightedW, (double) qz * weightedX);
        double nextZ = sumFour((double) qw * weightedZ, (double) qx * weightedY,
                -(double) qy * weightedX, (double) qz * weightedW);
        double nextW = sumFour((double) qw * weightedW, -(double) qx * weightedX,
                -(double) qy * weightedY, -(double) qz * weightedZ);
        setNormalizedRotationOr(nextX, nextY, nextZ, nextW, qx, qy, qz, qw);
    }

    void normalizeRotationOr(double fallbackX, double fallbackY, double fallbackZ, double fallbackW) {
        setNormalizedRotationOr(qx, qy, qz, qw, fallbackX, fallbackY, fallbackZ, fallbackW);
    }

    private void setNormalizedRotationOr(
            double valueX, double valueY, double valueZ, double valueW,
            double fallbackX, double fallbackY, double fallbackZ, double fallbackW) {
        double lengthSquared = sumFour(valueX * valueX, valueY * valueY, valueZ * valueZ, valueW * valueW);
        if (!Double.isFinite(lengthSquared) || lengthSquared <= QUATERNION_EPSILON) {
            qx = finite(fallbackX);
            qy = finite(fallbackY);
            qz = finite(fallbackZ);
            qw = finite(fallbackW);
            return;
        }
        double inverse = 1.0D / Math.sqrt(lengthSquared);
        qx = finite(valueX * inverse);
        qy = finite(valueY * inverse);
        qz = finite(valueZ * inverse);
        qw = finite(valueW * inverse);
    }

    private boolean hasSameCanonicalGroupRotation(AnimationV2TransformScratch other) {
        double currentSign = canonicalHemisphereSign(qx, qy, qz, qw);
        double otherSign = canonicalHemisphereSign(other.qx, other.qy, other.qz, other.qw);
        return (double) qx * currentSign == (double) other.qx * otherSign
                && (double) qy * currentSign == (double) other.qy * otherSign
                && (double) qz * currentSign == (double) other.qz * otherSign
                && (double) qw * currentSign == (double) other.qw * otherSign;
    }

    private void setCanonicalRotationFrom(AnimationV2TransformScratch sample) {
        double sign = canonicalHemisphereSign(sample.qx, sample.qy, sample.qz, sample.qw);
        qx = finite((double) sample.qx * sign);
        qy = finite((double) sample.qy * sign);
        qz = finite((double) sample.qz * sign);
        qw = finite((double) sample.qw * sign);
    }

    void canonicalizeRotation() {
        float sign = canonicalHemisphereSign(qx, qy, qz, qw);
        qx = finite((double) qx * (double) sign);
        qy = finite((double) qy * (double) sign);
        qz = finite((double) qz * (double) sign);
        qw = finite((double) qw * (double) sign);
    }

    Transform toTransform() {
        positiveFinite(scale);
        return new Transform(new Vec3(tx, ty, tz), new Quaternion(qx, qy, qz, qw), new Vec3(scale, scale, scale));
    }

    static float canonicalHemisphereSign(float x, float y, float z, float w) {
        return (float) canonicalHemisphereSign((double) x, (double) y, (double) z, (double) w);
    }

    private static double canonicalHemisphereSign(double x, double y, double z, double w) {
        if (Math.abs(w) > HEMISPHERE_EPSILON) {
            return w < 0.0D ? -1.0D : 1.0D;
        }
        if (Math.abs(x) > HEMISPHERE_EPSILON) {
            return x < 0.0D ? -1.0D : 1.0D;
        }
        if (Math.abs(y) > HEMISPHERE_EPSILON) {
            return y < 0.0D ? -1.0D : 1.0D;
        }
        return z < 0.0D ? -1.0D : 1.0D;
    }

    /** Aligns one normalized quaternion to a normalized canonical reference without allocating. */
    static float referenceHemisphereSign(
            float referenceX, float referenceY, float referenceZ, float referenceW,
            float valueX, float valueY, float valueZ, float valueW) {
        double dot = sumFour((double) referenceX * (double) valueX, (double) referenceY * (double) valueY,
                (double) referenceZ * (double) valueZ, (double) referenceW * (double) valueW);
        if (dot < -HEMISPHERE_EPSILON) {
            return -1.0F;
        }
        if (dot > HEMISPHERE_EPSILON) {
            return 1.0F;
        }
        return canonicalHemisphereSign(valueX, valueY, valueZ, valueW);
    }

    private void setInterpolated(
            float leftTx, float leftTy, float leftTz, float leftQx, float leftQy, float leftQz, float leftQw, float leftScale,
            float rightTx, float rightTy, float rightTz, float rightQx, float rightQy, float rightQz, float rightQw, float rightScale,
            double amount) {
        if (!Double.isFinite(amount) || amount < 0.0D || amount > 1.0D) {
            throw new IllegalArgumentException("interpolation amount must be finite and in [0, 1]");
        }
        tx = interpolate(leftTx, rightTx, amount);
        ty = interpolate(leftTy, rightTy, amount);
        tz = interpolate(leftTz, rightTz, amount);
        scale = positiveFinite((1.0D - amount) * (double) leftScale + amount * (double) rightScale);
        setSlerp(leftQx, leftQy, leftQz, leftQw, rightQx, rightQy, rightQz, rightQw, amount);
    }

    private static float interpolate(float left, float right, double amount) {
        return finite((1.0D - amount) * left + amount * right);
    }

    private void setSlerp(
            float leftX, float leftY, float leftZ, float leftW,
            float rightX, float rightY, float rightZ, float rightW,
            double amount) {
        double leftSign = canonicalHemisphereSign(leftX, leftY, leftZ, leftW);
        double normalizedLeftX = (double) leftX * leftSign;
        double normalizedLeftY = (double) leftY * leftSign;
        double normalizedLeftZ = (double) leftZ * leftSign;
        double normalizedLeftW = (double) leftW * leftSign;
        double rightSign = canonicalHemisphereSign(rightX, rightY, rightZ, rightW);
        double normalizedRightX = (double) rightX * rightSign;
        double normalizedRightY = (double) rightY * rightSign;
        double normalizedRightZ = (double) rightZ * rightSign;
        double normalizedRightW = (double) rightW * rightSign;
        if (amount == 0.0D) {
            setNormalizedRotationOr(
                    normalizedLeftX, normalizedLeftY, normalizedLeftZ, normalizedLeftW,
                    normalizedLeftX, normalizedLeftY, normalizedLeftZ, normalizedLeftW);
            return;
        }
        if (amount == 1.0D) {
            setNormalizedRotationOr(
                    normalizedRightX, normalizedRightY, normalizedRightZ, normalizedRightW,
                    normalizedRightX, normalizedRightY, normalizedRightZ, normalizedRightW);
            return;
        }
        double cosine = sumFour(normalizedLeftX * normalizedRightX, normalizedLeftY * normalizedRightY,
                normalizedLeftZ * normalizedRightZ, normalizedLeftW * normalizedRightW);
        if (cosine < 0.0D) {
            normalizedRightX = -normalizedRightX;
            normalizedRightY = -normalizedRightY;
            normalizedRightZ = -normalizedRightZ;
            normalizedRightW = -normalizedRightW;
            cosine = -cosine;
        }
        if (cosine > 0.9995D) {
            setNormalizedRotationOr(
                    normalizedLeftX + amount * (normalizedRightX - normalizedLeftX),
                    normalizedLeftY + amount * (normalizedRightY - normalizedLeftY),
                    normalizedLeftZ + amount * (normalizedRightZ - normalizedLeftZ),
                    normalizedLeftW + amount * (normalizedRightW - normalizedLeftW),
                    normalizedLeftX, normalizedLeftY, normalizedLeftZ, normalizedLeftW);
            return;
        }
        double theta = Math.acos(Math.min(1.0D, Math.max(-1.0D, cosine)));
        double sine = Math.sin(theta);
        double leftWeight = Math.sin((1.0D - amount) * theta) / sine;
        double rightWeight = Math.sin(amount * theta) / sine;
        setNormalizedRotationOr(
                leftWeight * normalizedLeftX + rightWeight * normalizedRightX,
                leftWeight * normalizedLeftY + rightWeight * normalizedRightY,
                leftWeight * normalizedLeftZ + rightWeight * normalizedRightZ,
                leftWeight * normalizedLeftW + rightWeight * normalizedRightW,
                normalizedLeftX, normalizedLeftY, normalizedLeftZ, normalizedLeftW);
    }

    private static float finite(double value) {
        float result = (float) value;
        if (!Double.isFinite(value) || !Float.isFinite(result)) {
            throw new IllegalArgumentException("v2 transform scratch produced a non-finite value");
        }
        return result;
    }

    /**
     * Sums four finite double products without allocating. Sorting by magnitude makes exact cancellation of the large
     * Hamilton terms occur before a representable float subnormal is added, rather than silently rounding it away.
     */
    private static double sumFour(double first, double second, double third, double fourth) {
        if (Math.abs(first) < Math.abs(second)) {
            double swap = first;
            first = second;
            second = swap;
        }
        if (Math.abs(third) < Math.abs(fourth)) {
            double swap = third;
            third = fourth;
            fourth = swap;
        }
        if (Math.abs(first) < Math.abs(third)) {
            double swap = first;
            first = third;
            third = swap;
        }
        if (Math.abs(second) < Math.abs(fourth)) {
            double swap = second;
            second = fourth;
            fourth = swap;
        }
        if (Math.abs(second) < Math.abs(third)) {
            double swap = second;
            second = third;
            third = swap;
        }
        return ((first + second) + third) + fourth;
    }

    /**
     * No-allocation magnitude ordering for additive translation's current + weighted-sample - weighted-rest. The two
     * largest opposite terms (normally current and -weighted-rest) are merged first, so their exact cancellation is
     * complete before the remaining legal weighted-sample subnormal is added; all three operands are already double.
     */
    private static double sumThree(double first, double second, double third) {
        if (Math.abs(first) < Math.abs(second)) {
            double swap = first;
            first = second;
            second = swap;
        }
        if (Math.abs(second) < Math.abs(third)) {
            double swap = second;
            second = third;
            third = swap;
        }
        if (Math.abs(first) < Math.abs(second)) {
            double swap = first;
            first = second;
            second = swap;
        }
        return (first + second) + third;
    }

    private static float positiveFinite(double value) {
        float result = finite(value);
        if (result <= 0.0F) {
            throw new IllegalArgumentException("v2 transform scratch produced a non-positive scale");
        }
        return result;
    }
}
