package com.liy.blendlib.core.procedural;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import java.util.Objects;

/** Small pure-Java math and hostile-input helpers shared only inside the X3 package. */
final class ProceduralSupport {
    private ProceduralSupport() {
    }

    static void requireId(BlendResourceId id, String name) {
        Objects.requireNonNull(id, name);
        if (id.value().length() > ProceduralLimits.MAX_IDENTIFIER_UTF16_CODE_UNITS) {
            throw new IllegalArgumentException(name + " exceeds X3 identity budget");
        }
    }

    static void requireBoundedVector(Vec3 value, String name) {
        Objects.requireNonNull(value, name);
        if (Math.abs(value.x()) > ProceduralLimits.MAX_TRANSLATION_MAGNITUDE
                || Math.abs(value.y()) > ProceduralLimits.MAX_TRANSLATION_MAGNITUDE
                || Math.abs(value.z()) > ProceduralLimits.MAX_TRANSLATION_MAGNITUDE) {
            throw new IllegalArgumentException(name + " exceeds procedural translation bounds");
        }
    }

    static String boundedText(String raw, int maximum) {
        Objects.requireNonNull(raw, "raw");
        StringBuilder output = new StringBuilder(Math.min(raw.length(), maximum));
        for (int index = 0; index < raw.length() && output.length() < maximum; index++) {
            char current = raw.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 < raw.length() && Character.isLowSurrogate(raw.charAt(index + 1))) {
                    if (output.length() + 2 > maximum) {
                        break;
                    }
                    output.append(current).append(raw.charAt(++index));
                } else {
                    output.append('?');
                }
            } else if (Character.isLowSurrogate(current)) {
                output.append('?');
            } else if (Character.isISOControl(current)) {
                output.append(' ');
            } else {
                output.append(current);
            }
        }
        return output.toString();
    }

    /**
     * Sanitizes bounded caller text without ever converting an over-limit value into a different accepted value.
     * Diagnostic text may intentionally truncate, but semantic event payloads must fail closed instead.
     */
    static String requireBoundedText(String raw, int maximum, String name) {
        Objects.requireNonNull(raw, name);
        if (raw.length() > maximum) {
            throw new IllegalArgumentException(name + " exceeds the X3 UTF-16 code-unit limit");
        }
        return boundedText(raw, maximum);
    }

    static Transform composeOffset(Transform base, Vec3 translationOffset, Quaternion rotationOffset, float scaleMultiplier) {
        requireBoundedVector(translationOffset, "translationOffset");
        if (!Float.isFinite(scaleMultiplier) || scaleMultiplier < ProceduralLimits.MIN_SCALE_MULTIPLIER
                || scaleMultiplier > ProceduralLimits.MAX_SCALE_MULTIPLIER) {
            throw new IllegalArgumentException("scale multiplier is outside X3 bounds");
        }
        Vec3 translation = base.translation().add(translationOffset);
        float scaleX = base.scale().x() * scaleMultiplier;
        float scaleY = base.scale().y() * scaleMultiplier;
        float scaleZ = base.scale().z() * scaleMultiplier;
        if (!Float.isFinite(scaleX) || !Float.isFinite(scaleY) || !Float.isFinite(scaleZ)
                || scaleX <= 0.0F || scaleY <= 0.0F || scaleZ <= 0.0F) {
            throw new IllegalArgumentException("offset scale underflowed or overflowed strict-v1 scale semantics");
        }
        return new Transform(translation, canonicalQuaternion(
                canonicalQuaternion(rotationOffset).multiply(canonicalQuaternion(base.rotation()))),
                new Vec3(scaleX, scaleY, scaleZ));
    }

    /**
     * Normalizes a finite non-zero quaternion, maps q and -q to one sign convention, and removes negative zero.
     * This is both the execution representation and the canonical ordering representation for X3 operations.
     */
    static Quaternion canonicalQuaternion(Quaternion value) {
        Objects.requireNonNull(value, "quaternion");
        Quaternion normalized = value.normalized();
        float[] components = {normalized.x(), normalized.y(), normalized.z(), normalized.w()};
        int signIndex = 0;
        for (int index = 1; index < components.length; index++) {
            if (Math.abs(components[index]) > Math.abs(components[signIndex])) {
                signIndex = index;
            }
        }
        if (components[signIndex] < 0.0F) {
            for (int index = 0; index < components.length; index++) {
                components[index] = -components[index];
            }
        }
        for (int index = 0; index < components.length; index++) {
            if (components[index] == 0.0F) {
                components[index] = 0.0F;
            }
        }
        return new Quaternion(components[0], components[1], components[2], components[3]);
    }

    static String canonicalQuaternionKey(Quaternion value) {
        Quaternion canonical = canonicalQuaternion(value);
        return canonicalFloatKey(canonical.x()) + ':' + canonicalFloatKey(canonical.y()) + ':'
                + canonicalFloatKey(canonical.z()) + ':' + canonicalFloatKey(canonical.w());
    }

    static String canonicalFloatKey(float value) {
        float normalizedZero = value == 0.0F ? 0.0F : value;
        String bits = Integer.toUnsignedString(Float.floatToIntBits(normalizedZero), 16);
        return "00000000".substring(bits.length()) + bits;
    }

    static Quaternion inverse(Quaternion rotation) {
        return new Quaternion(-rotation.x(), -rotation.y(), -rotation.z(), rotation.w()).normalized();
    }

    static Vec3 inverseRotate(Quaternion rotation, Vec3 value) {
        return inverse(rotation).rotate(value);
    }

    static Quaternion yawPitch(float yaw, float pitch) {
        float halfYaw = yaw * 0.5F;
        float halfPitch = pitch * 0.5F;
        Quaternion yawRotation = new Quaternion(0.0F, (float) Math.sin(halfYaw), 0.0F, (float) Math.cos(halfYaw));
        Quaternion pitchRotation = new Quaternion((float) Math.sin(halfPitch), 0.0F, 0.0F, (float) Math.cos(halfPitch));
        return yawRotation.multiply(pitchRotation);
    }

    static float shortestAngle(float value) {
        float result = (float) Math.IEEEremainder(value, Math.PI * 2.0D);
        return result == -Math.PI ? (float) Math.PI : result;
    }

    static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
