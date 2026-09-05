package com.liy.blendlib.fabric.client.reload;

import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import com.liy.blendlib.fabric.client.render.X6DrawPrimitive;
import java.util.Objects;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/** Immutable, per-frame direct-static inputs kept separate from D1 policy publication. */
final class StaticDirectFrameInputs {
    private final ModelRenderSnapshot exactSnapshot;
    private final X6DrawPrimitive exactDraw;
    private final StaticDirectOpaqueMarker exactMarker;
    private final StaticDirectPoseSnapshot exactPose;
    private final Matrix4f modelView;
    private final Matrix3f normalTransform;
    private final Vector4f colorModulator;
    private final int packedLight;

    private StaticDirectFrameInputs(
            ModelRenderSnapshot exactSnapshot,
            X6DrawPrimitive exactDraw,
            StaticDirectOpaqueMarker exactMarker,
            StaticDirectPoseSnapshot exactPose) {
        this.exactSnapshot = exactSnapshot;
        this.exactDraw = exactDraw;
        this.exactMarker = exactMarker;
        this.exactPose = exactPose;
        this.modelView = exactPose.modelViewCopy();
        this.normalTransform = exactPose.normalTransformCopy();
        this.colorModulator = color(multiplyArgb(
                multiplyArgb(exactSnapshot.tintArgb(), exactMarker.exactMaterial().argbTint()), exactDraw.argbTint()));
        this.packedLight = StaticDirectPackedLight.requireExactlyRepresentable(exactMarker.resolvePackedLight(exactSnapshot));
    }

    static StaticDirectFrameInputs create(
            ModelRenderSnapshot snapshot,
            X6DrawPrimitive draw,
            StaticDirectOpaqueMarker marker,
            StaticDirectPoseSnapshot exactPose) {
        ModelRenderSnapshot checkedSnapshot = Objects.requireNonNull(snapshot, "snapshot");
        X6DrawPrimitive checkedDraw = Objects.requireNonNull(draw, "draw");
        StaticDirectOpaqueMarker checkedMarker = Objects.requireNonNull(marker, "marker");
        StaticDirectPoseSnapshot checkedPose = Objects.requireNonNull(exactPose, "exactPose");
        if (!checkedMarker.matchesExactly(checkedSnapshot, checkedDraw)) {
            throw new IllegalArgumentException("The direct-static marker is not the exact snapshot/draw marker");
        }
        if (!StaticDirectOpaqueMarker.isExactNeutralOverlay(checkedSnapshot.packedOverlay())) {
            throw new IllegalArgumentException("The direct-static frame does not retain the exact neutral overlay");
        }
        return new StaticDirectFrameInputs(checkedSnapshot, checkedDraw, checkedMarker, checkedPose);
    }

    ModelRenderSnapshot exactSnapshot() {
        return exactSnapshot;
    }

    X6DrawPrimitive exactDraw() {
        return exactDraw;
    }

    StaticDirectOpaqueMarker exactMarker() {
        return exactMarker;
    }

    StaticDirectPoseSnapshot exactPose() {
        return exactPose;
    }

    Matrix4f modelViewCopy() {
        return new Matrix4f(modelView);
    }

    Matrix3f normalTransformCopy() {
        return new Matrix3f(normalTransform);
    }

    Vector4f colorModulatorCopy() {
        return new Vector4f(colorModulator);
    }

    int packedLight() {
        return packedLight;
    }

    int packedOverlay() {
        return exactSnapshot.packedOverlay();
    }

    int vertexCount() {
        return exactMarker.exactPrimitive().geometry().vertexCount();
    }

    int indexCount() {
        return exactMarker.exactPrimitive().geometry().indexCount();
    }

    private static Vector4f color(int argb) {
        return new Vector4f(
                (argb >>> 16 & 0xFF) / 255.0F,
                (argb >>> 8 & 0xFF) / 255.0F,
                (argb & 0xFF) / 255.0F,
                (argb >>> 24) / 255.0F);
    }

    private static int multiplyArgb(int left, int right) {
        return multiplyChannel(left >>> 24, right >>> 24) << 24
                | multiplyChannel(left >>> 16 & 0xFF, right >>> 16 & 0xFF) << 16
                | multiplyChannel(left >>> 8 & 0xFF, right >>> 8 & 0xFF) << 8
                | multiplyChannel(left & 0xFF, right & 0xFF);
    }

    private static int multiplyChannel(int left, int right) {
        return left * right / 255;
    }
}
