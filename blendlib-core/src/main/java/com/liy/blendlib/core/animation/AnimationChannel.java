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

    public AnimationChannel(int targetNode, AnimationPath path, Interpolation interpolation, float[] times, float[] values) {
        if (targetNode < 0) {
            throw new IllegalArgumentException("Animation target node must be non-negative");
        }
        this.targetNode = targetNode;
        this.path = Objects.requireNonNull(path, "path");
        this.interpolation = Objects.requireNonNull(interpolation, "interpolation");
        this.times = copyFinite(times, "times");
        this.values = copyFinite(values, "values");
        if (this.times.length == 0 || this.values.length != this.times.length * path.components()) {
            throw new IllegalArgumentException("Animation key values do not match their time count and target path");
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

    /** Samples one key-framed value at a finite time using v1 STEP/vector/rotation rules. */
    public float[] sample(float timeSeconds) {
        if (!Float.isFinite(timeSeconds)) {
            throw new IllegalArgumentException("Sample time must be finite");
        }
        int key = keyAtOrBefore(timeSeconds);
        if (key == times.length - 1 || interpolation == Interpolation.STEP) {
            return valueAt(key);
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
