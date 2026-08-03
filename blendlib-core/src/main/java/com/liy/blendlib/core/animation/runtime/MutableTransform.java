package com.liy.blendlib.core.animation.runtime;

import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;

/** Package-local sampling scratch state, converted to an immutable transform before publication. */
final class MutableTransform {
    private float translationX;
    private float translationY;
    private float translationZ;
    private float rotationX;
    private float rotationY;
    private float rotationZ;
    private float rotationW;
    private float scaleX;
    private float scaleY;
    private float scaleZ;

    MutableTransform(Transform source) {
        translationX = source.translation().x();
        translationY = source.translation().y();
        translationZ = source.translation().z();
        rotationX = source.rotation().x();
        rotationY = source.rotation().y();
        rotationZ = source.rotation().z();
        rotationW = source.rotation().w();
        scaleX = source.scale().x();
        scaleY = source.scale().y();
        scaleZ = source.scale().z();
    }

    void setTranslation(float x, float y, float z) {
        translationX = x;
        translationY = y;
        translationZ = z;
    }

    void setRotation(float x, float y, float z, float w) {
        rotationX = x;
        rotationY = y;
        rotationZ = z;
        rotationW = w;
    }

    void setScale(float x, float y, float z) {
        scaleX = x;
        scaleY = y;
        scaleZ = z;
    }

    Transform freeze() {
        return new Transform(
                new Vec3(translationX, translationY, translationZ),
                new Quaternion(rotationX, rotationY, rotationZ, rotationW),
                new Vec3(scaleX, scaleY, scaleZ));
    }
}
