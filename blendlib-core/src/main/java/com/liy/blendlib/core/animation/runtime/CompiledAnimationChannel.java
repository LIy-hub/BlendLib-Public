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
    private final AnimationChannel source;

    private CompiledAnimationChannel(AnimationChannel source) {
        this.targetNode = source.targetNode();
        this.path = source.path();
        this.interpolation = source.interpolation();
        this.source = source;
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
        if (key == source.keyCount() - 1 || interpolation == Interpolation.STEP
                || (source.clampsEndpoints() && timeSeconds <= source.keyTime(0)) || (source.clampsEndpoints() && timeSeconds == source.keyTime(key))) {
            applyKey(key, target);
            return;
        }

        if (interpolation == Interpolation.CUBICSPLINE) {
            double amount = (timeSeconds - source.keyTime(key)) / ((double) source.keyTime(key + 1) - source.keyTime(key));
            double x = source.cubicComponent(key, 0, amount), y = source.cubicComponent(key, 1, amount);
            double z = source.cubicComponent(key, 2, amount);
            switch (path) {
                case TRANSLATION -> target.setTranslation(finite(x), finite(y), finite(z));
                case SCALE -> target.setScale(finite(x), finite(y), finite(z));
                case ROTATION -> {
                    double w = source.cubicComponent(key, 3, amount);
                    double norm = Math.hypot(Math.hypot(x, y), Math.hypot(z, w));
                    if (!(norm > 1.0e-6)) throw new IllegalArgumentException("Cubic quaternion is not normalizable");
                    target.setRotation(finite(x / norm), finite(y / norm), finite(z / norm), finite(w / norm));
                }
            }
            return;
        }
        float fraction = (float) ((timeSeconds - source.keyTime(key)) / (source.keyTime(key + 1) - source.keyTime(key)));
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
        if (timeSeconds <= source.keyTime(0)) {
            return 0;
        }
        if (timeSeconds >= source.keyTime(source.keyCount() - 1)) {
            return source.keyCount() - 1;
        }
        int low = 0;
        int high = source.keyCount() - 1;
        while (low + 1 < high) {
            int middle = (low + high) >>> 1;
            if (source.keyTime(middle) <= timeSeconds) {
                low = middle;
            } else {
                high = middle;
            }
        }
        return low;
    }

    private void applyKey(int key, MutableTransform target) {
        switch (path) {
            case TRANSLATION -> target.setTranslation(value(key, 0), value(key, 1), value(key, 2));
            case SCALE -> target.setScale(value(key, 0), value(key, 1), value(key, 2));
            case ROTATION -> target.setRotation(value(key, 0), value(key, 1), value(key, 2), value(key, 3));
        }
    }

    private float linear(int key, int component, float fraction) {
        float value = value(key, component) + fraction * (value(key + 1, component) - value(key, component));
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("Animation interpolation produced a non-finite component");
        }
        return value;
    }

    private float value(int key, int component) { return source.keyValue(key, component); }

    private static float finite(double value) {
        float result = (float) value;
        if (!Double.isFinite(value) || !Float.isFinite(result)) throw new IllegalArgumentException("Non-finite cubic result");
        return result;
    }

    private Quaternion quaternionAt(int key) {
        return new Quaternion(value(key, 0), value(key, 1), value(key, 2), value(key, 3));
    }
}
