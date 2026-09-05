package com.liy.blendlib.fabric.client.reload;

import java.util.Objects;
import org.joml.Matrix3f;
import org.joml.Matrix3fc;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * Stage-A copy of the caller's already-final model-view and inverse-transpose normal transform.
 *
 * <p>The type deliberately accepts matrices rather than a {@code PoseStack}: T3c must freeze these while the live
 * caller context exists, and Phase B receives only these value copies.</p>
 */
final class StaticDirectPoseSnapshot {
    private final Matrix4f finalModelView;
    private final Matrix3f finalNormalTransform;

    private StaticDirectPoseSnapshot(Matrix4fc finalModelView, Matrix3fc finalNormalTransform) {
        Matrix4fc checkedModelView = Objects.requireNonNull(finalModelView, "finalModelView");
        Matrix3fc checkedNormalTransform = Objects.requireNonNull(finalNormalTransform, "finalNormalTransform");
        if (!checkedModelView.isFinite() || !checkedNormalTransform.isFinite()) {
            throw new IllegalArgumentException("The direct-static pose snapshot requires finite caller transforms");
        }
        this.finalModelView = new Matrix4f(checkedModelView);
        this.finalNormalTransform = new Matrix3f(checkedNormalTransform);
    }

    static StaticDirectPoseSnapshot freeze(Matrix4fc finalModelView, Matrix3fc finalNormalTransform) {
        return new StaticDirectPoseSnapshot(finalModelView, finalNormalTransform);
    }

    Matrix4f modelViewCopy() {
        return new Matrix4f(finalModelView);
    }

    Matrix3f normalTransformCopy() {
        return new Matrix3f(finalNormalTransform);
    }
}
