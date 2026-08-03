package com.liy.blendlib.fabric.client.entity;

import com.liy.blendlib.core.animation.runtime.LocalPose;
import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.Transform;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Internal conversion boundary between the outer entity adapter and nested core pose types. */
final class BlendEntityRotationPoseAdapter {
    private BlendEntityRotationPoseAdapter() {
    }

    static BlendEntityRotationPose capture(LocalPose basePose) {
        LocalPose checkedBase = Objects.requireNonNull(basePose, "basePose");
        LinkedHashMap<Integer, BlendEntityRotation> rotations = new LinkedHashMap<>();
        for (Map.Entry<Integer, Transform> entry : checkedBase.transforms().entrySet()) {
            Quaternion rotation = entry.getValue().rotation();
            rotations.put(entry.getKey(), new BlendEntityRotation(
                    rotation.x(), rotation.y(), rotation.z(), rotation.w()));
        }
        return BlendEntityRotationPose.capture(rotations);
    }

    static LocalPose apply(
            LocalPose basePose,
            BlendEntityRotationPose capturedBase,
            BlendEntityRotationPose modifiedPose) {
        LocalPose checkedBase = Objects.requireNonNull(basePose, "basePose");
        BlendEntityRotationPose checkedCapturedBase = Objects.requireNonNull(capturedBase, "capturedBase");
        if (modifiedPose == null) {
            throw new IllegalArgumentException("Entity pose modifier must return a non-null rotation pose");
        }
        if (!modifiedPose.isDerivedFrom(checkedCapturedBase)) {
            throw new IllegalArgumentException(
                    "Entity pose modifier must return the supplied rotation pose or one derived from it");
        }
        if (!checkedBase.transforms().keySet().equals(checkedCapturedBase.nodeIndices())) {
            throw new IllegalArgumentException("Captured entity rotation pose does not match the core base-pose node set");
        }
        if (modifiedPose.rotationOverrides().isEmpty()) {
            return checkedBase;
        }

        LinkedHashMap<Integer, Transform> transforms = new LinkedHashMap<>(checkedBase.transforms());
        for (Map.Entry<Integer, BlendEntityRotation> entry : modifiedPose.rotationOverrides().entrySet()) {
            int nodeIndex = entry.getKey();
            Transform baseTransform = checkedBase.transform(nodeIndex);
            BlendEntityRotation rotation = entry.getValue();
            transforms.put(nodeIndex, new Transform(
                    baseTransform.translation(),
                    new Quaternion(rotation.x(), rotation.y(), rotation.z(), rotation.w()),
                    baseTransform.scale()));
        }
        return new LocalPose(transforms);
    }
}
