package com.liy.blendlib.core.animation;

import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import java.util.Arrays;
import java.util.Objects;

/** Immutable sampled node channel with finite, strictly increasing key times. */
public final class AnimationChannel {
    private final int targetNode;
    private final AnimationPath path;
    private final Interpolation interpolation;
    private final float[] times;
    private final float[] values;
    private final float[] inTangents;
    private final float[] outTangents;
    private final boolean clampEndpoints;
    private final double cubicMagnitudeBound;

    public AnimationChannel(int targetNode, AnimationPath path, Interpolation interpolation, float[] times, float[] values) {
        this(targetNode, path, interpolation, times, values, new float[0], new float[0], false);
    }

    /** Explicit new-profile entry point; tangent arrays contain one vector per key, in units/second. */
    public static AnimationChannel forCubicProfile(int targetNode, AnimationPath path,
            Interpolation interpolation, float[] times, float[] values, float[] inTangents, float[] outTangents) {
        return new AnimationChannel(targetNode, path, interpolation, times, values, inTangents, outTangents, true);
    }

    private AnimationChannel(int targetNode, AnimationPath path, Interpolation interpolation,
            float[] times, float[] values, float[] inTangents, float[] outTangents, boolean clampEndpoints) {
        if (targetNode < 0) {
            throw new IllegalArgumentException("Animation target node must be non-negative");
        }
        this.targetNode = targetNode;
        this.path = Objects.requireNonNull(path, "path");
        this.interpolation = Objects.requireNonNull(interpolation, "interpolation");
        this.times = copyFinite(times, "times");
        this.values = copyFinite(values, "values");
        this.inTangents = copyFinite(inTangents, "inTangents");
        this.outTangents = copyFinite(outTangents, "outTangents");
        this.clampEndpoints = clampEndpoints;
        if (this.times.length == 0 || this.values.length != (long) this.times.length * path.components()) {
            throw new IllegalArgumentException("Animation key values do not match their time count and target path");
        }
        if (interpolation == Interpolation.CUBICSPLINE) {
            if (!clampEndpoints || this.times.length < 2 || this.inTangents.length != this.values.length
                    || this.outTangents.length != this.values.length) {
                throw new IllegalArgumentException("Cubic channels require >=2 keys and separate matching tangent vectors");
            }
        } else if (this.inTangents.length != 0 || this.outTangents.length != 0) {
            throw new IllegalArgumentException("Only cubic channels may contain tangent vectors");
        }
        for (int index = 1; index < this.times.length; index++) {
            if (!(this.times[index] > this.times[index - 1])) {
                throw new IllegalArgumentException("Animation key times must be strictly increasing");
            }
        }
        if (path == AnimationPath.ROTATION) {
            for (int index = 0; index < this.values.length; index += 4) {
                new Quaternion(this.values[index], this.values[index + 1], this.values[index + 2], this.values[index + 3]).normalized();
            }
        }
        if (path == AnimationPath.SCALE) {
            for (int index = 0; index < this.values.length; index += 3) {
                Transform.validateStrictV1Scale(new Vec3(this.values[index], this.values[index + 1], this.values[index + 2]));
            }
        }
        this.cubicMagnitudeBound = interpolation == Interpolation.CUBICSPLINE ? validateCubic() : 0.0;
    }

    public int targetNode() {
        return targetNode;
    }

    public AnimationPath path() {
        return path;
    }

    public Interpolation interpolation() {
        return interpolation;
    }

    public int keyCount() {
        return times.length;
    }

    public float durationSeconds() {
        return times[times.length - 1];
    }

    public float[] times() {
        return Arrays.copyOf(times, times.length);
    }

    public float[] values() {
        return Arrays.copyOf(values, values.length);
    }

    /** Returns defensive copies; values() remains exactly one vector per key. */
    public float[] inTangents() { return Arrays.copyOf(inTangents, inTangents.length); }
    public float[] outTangents() { return Arrays.copyOf(outTangents, outTangents.length); }
    public boolean clampsEndpoints() { return clampEndpoints; }
    /** Indexed immutable access lets every compiled state share the same generation-owned arrays. */
    public float keyTime(int key) { return times[key]; }
    public float keyValue(int key, int component) { return values[key * path.components() + component]; }
    public double cubicMagnitudeBound() { return cubicMagnitudeBound; }

    /** Evaluates the Hermite polynomial through its equivalent Bezier control hull, without allocation. */
    public double cubicComponent(int key, int component, double amount) {
        if (interpolation != Interpolation.CUBICSPLINE || !Double.isFinite(amount) || amount < 0 || amount > 1) {
            throw new IllegalArgumentException("Invalid cubic sample");
        }
        double a = control(key, 0, component), b = control(key, 1, component);
        double c = control(key, 2, component), d = control(key, 3, component);
        double ab = lerp(a, b, amount), bc = lerp(b, c, amount), cd = lerp(c, d, amount);
        return lerp(lerp(ab, bc, amount), lerp(bc, cd, amount), amount);
    }

    private static double lerp(double a, double b, double t) { return (1.0 - t) * a + t * b; }

    private double control(int key, int point, int component) {
        int n = path.components(), offset = key * n + component;
        double duration = (double) times[key + 1] - times[key];
        return switch (point) {
            case 0 -> values[offset];
            case 1 -> values[offset] + duration * outTangents[offset] / 3.0;
            case 2 -> values[offset + n] - duration * inTangents[offset + n] / 3.0;
            case 3 -> values[offset + n];
            default -> throw new IllegalArgumentException("Invalid Bezier control");
        };
    }

    private double validateCubic() {
        double maximum = 0.0;
        if (path == AnimationPath.SCALE) {
            for (int i = 0; i < values.length; i += 3) {
                if (values[i] != values[i + 1] || values[i] != values[i + 2]
                        || inTangents[i] != inTangents[i + 1] || inTangents[i] != inTangents[i + 2]
                        || outTangents[i] != outTangents[i + 1] || outTangents[i] != outTangents[i + 2]) {
                    throw new IllegalArgumentException("Cubic scale must have exactly uniform values and tangents");
                }
            }
        }
        if (path == AnimationPath.ROTATION) {
            for (int key = 0; key < times.length; key++) {
                double norm = 0;
                for (int c = 0; c < 4; c++) norm = Math.hypot(norm, keyValue(key, c));
                if (Math.abs(norm - 1.0) > 1.0e-4) {
                    throw new IllegalArgumentException("Cubic quaternion keys must be unit length; tangents are not normalized");
                }
            }
        }
        for (int key = 0; key < times.length - 1; key++) {
            double maxControlNorm = 0.0;
            for (int point = 0; point < 4; point++) {
                double norm = 0.0;
                for (int c = 0; c < path.components(); c++) {
                    double value = control(key, point, c);
                    if (!Double.isFinite(value) || Math.abs(value) > Float.MAX_VALUE / 4.0) {
                        throw new IllegalArgumentException("Cubic control exceeds the finite preparation envelope");
                    }
                    norm = Math.hypot(norm, value);
                }
                maxControlNorm = Math.max(maxControlNorm, norm);
                if (path == AnimationPath.SCALE) {
                    double value = control(key, point, 0);
                    if (value < 1.0e-6) {
                        throw new IllegalArgumentException("Cubic scale control hull cannot prove strictly positive interior scale");
                    }
                    maximum = Math.max(maximum, value);
                }
            }
            if (path == AnimationPath.TRANSLATION) maximum = Math.max(maximum, maxControlNorm);
            if (maxControlNorm > Float.MAX_VALUE / 4.0) {
                throw new IllegalArgumentException("Cubic control norm exceeds the finite preparation envelope");
            }
            if (path == AnimationPath.ROTATION) {
                double norm = 0.0;
                for (int c = 0; c < 4; c++) norm = Math.hypot(norm, keyValue(key, c));
                // A positive projection for the whole convex hull proves norm cannot approach zero.
                // Relative margin dominates double control/dot-product rounding and evaluation error.
                double margin = 1.0e-5 + 1.0e-12 * maxControlNorm;
                for (int point = 0; point < 4; point++) {
                    double projection = 0.0;
                    for (int c = 0; c < 4; c++) projection += control(key, point, c) * (keyValue(key, c) / norm);
                    if (!(projection >= margin)) {
                        throw new IllegalArgumentException("Cubic quaternion control hull cannot prove a nonzero segment; signs are preserved");
                    }
                }
            }
        }
        return maximum;
    }

    /** Samples one key-framed value at a finite time using v1 STEP/vector/rotation rules. */
    public float[] sample(float timeSeconds) {
        if (!Float.isFinite(timeSeconds)) {
            throw new IllegalArgumentException("Sample time must be finite");
        }
        int key = keyAtOrBefore(timeSeconds);
        if (key == times.length - 1 || interpolation == Interpolation.STEP
                || (clampEndpoints && timeSeconds <= times[0]) || (clampEndpoints && timeSeconds == times[key])) {
            return valueAt(key);
        }
        if (interpolation == Interpolation.CUBICSPLINE) {
            double amount = ((double) timeSeconds - times[key]) / ((double) times[key + 1] - times[key]);
            float[] result = new float[path.components()];
            double norm = 0.0;
            if (path == AnimationPath.ROTATION) {
                for (int c = 0; c < 4; c++) norm = Math.hypot(norm, cubicComponent(key, c, amount));
                if (!(norm > 1.0e-6)) throw new IllegalArgumentException("Cubic quaternion is not normalizable");
            }
            for (int c = 0; c < result.length; c++) {
                double value = cubicComponent(key, c, amount);
                result[c] = finiteFloat(path == AnimationPath.ROTATION ? value / norm : value);
            }
            return result;
        }
        float fraction = (timeSeconds - times[key]) / (times[key + 1] - times[key]);
        if (path == AnimationPath.ROTATION) {
            Quaternion result = Quaternion.slerp(quaternionAt(key), quaternionAt(key + 1), fraction);
            return new float[] {result.x(), result.y(), result.z(), result.w()};
        }
        float[] result = new float[path.components()];
        int offset = key * path.components();
        int nextOffset = offset + path.components();
        for (int component = 0; component < result.length; component++) {
            result[component] = values[offset + component] + fraction * (values[nextOffset + component] - values[offset + component]);
        }
        return result;
    }

    private int keyAtOrBefore(float timeSeconds) {
        if (timeSeconds <= times[0]) {
            return 0;
        }
        if (timeSeconds >= times[times.length - 1]) {
            return times.length - 1;
        }
        int low = 0;
        int high = times.length - 1;
        while (low + 1 < high) {
            int middle = (low + high) >>> 1;
            if (times[middle] <= timeSeconds) {
                low = middle;
            } else {
                high = middle;
            }
        }
        return low;
    }

    private float[] valueAt(int key) {
        int offset = key * path.components();
        return Arrays.copyOfRange(values, offset, offset + path.components());
    }

    private Quaternion quaternionAt(int key) {
        int offset = key * 4;
        return new Quaternion(values[offset], values[offset + 1], values[offset + 2], values[offset + 3]);
    }

    private static float finiteFloat(double value) {
        float result = (float) value;
        if (!Double.isFinite(value) || !Float.isFinite(result)) throw new IllegalArgumentException("Non-finite cubic result");
        return result;
    }

    private static float[] copyFinite(float[] values, String name) {
        float[] copy = Arrays.copyOf(Objects.requireNonNull(values, name), values.length);
        for (float value : copy) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException(name + " must contain only finite values");
            }
        }
        return copy;
    }
}
