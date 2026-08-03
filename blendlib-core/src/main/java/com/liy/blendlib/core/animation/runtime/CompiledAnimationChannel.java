package com.liy.blendlib.core.animation.runtime;

import com.liy.blendlib.core.animation.AnimationChannel;
import com.liy.blendlib.core.animation.AnimationPath;
import com.liy.blendlib.core.animation.Interpolation;
import com.liy.blendlib.core.model.Quaternion;
import java.util.Objects;

/** Internal immutable channel copy prepared once outside the animation hot path. */
final class CompiledAnimationChannel {
    private final int targetNode;
    private final AnimationPath path;
    private final Interpolation interpolation;
    private final float[] times;
    private final float[] values;

    private CompiledAnimationChannel(AnimationChannel source) {
        this.targetNode = source.targetNode();
        this.path = source.path();
        this.interpolation = source.interpolation();
        this.times = source.times();
        this.values = source.values();
    }

    static CompiledAnimationChannel compile(AnimationChannel source) {
        return new CompiledAnimationChannel(Objects.requireNonNull(source, "source"));
    }

    int targetNode() {
        return targetNode;
    }

    void apply(double timeSeconds, MutableTransform target) {
        Objects.requireNonNull(target, "target");
        int key = keyAtOrBefore(timeSeconds);
        if (key == times.length - 1 || interpolation == Interpolation.STEP) {
            applyKey(key, target);
            return;
        }

        float fraction = (float) ((timeSeconds - times[key]) / (times[key + 1] - times[key]));
        switch (path) {
            case TRANSLATION -> target.setTranslation(
                    linear(key, 0, fraction), linear(key, 1, fraction), linear(key, 2, fraction));
            case SCALE -> target.setScale(
                    linear(key, 0, fraction), linear(key, 1, fraction), linear(key, 2, fraction));
            case ROTATION -> {
                Quaternion rotation = Quaternion.slerp(quaternionAt(key), quaternionAt(key + 1), fraction);
                target.setRotation(rotation.x(), rotation.y(), rotation.z(), rotation.w());
            }
        }
    }

    private int keyAtOrBefore(double timeSeconds) {
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

    private void applyKey(int key, MutableTransform target) {
        int offset = key * path.components();
        switch (path) {
            case TRANSLATION -> target.setTranslation(values[offset], values[offset + 1], values[offset + 2]);
            case SCALE -> target.setScale(values[offset], values[offset + 1], values[offset + 2]);
            case ROTATION -> target.setRotation(values[offset], values[offset + 1], values[offset + 2], values[offset + 3]);
        }
    }

    private float linear(int key, int component, float fraction) {
        int offset = key * path.components() + component;
        float value = values[offset] + fraction * (values[offset + path.components()] - values[offset]);
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("Animation interpolation produced a non-finite component");
        }
        return value;
    }

    private Quaternion quaternionAt(int key) {
        int offset = key * 4;
        return new Quaternion(values[offset], values[offset + 1], values[offset + 2], values[offset + 3]);
    }
}
