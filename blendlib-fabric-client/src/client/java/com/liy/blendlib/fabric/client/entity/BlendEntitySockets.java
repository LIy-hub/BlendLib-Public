package com.liy.blendlib.fabric.client.entity;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import com.liy.blendlib.fabric.client.animation.extract.ClientSkinnedExtractionFrame;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable, generation-bound final-pose sockets. Safe to retain; no entity, world or runtime references. */
public record BlendEntitySockets(long generation, Map<BlendResourceId, BlendEntitySocket> sockets) {
    public BlendEntitySockets {
        if (generation < 0) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
        sockets = Map.copyOf(Objects.requireNonNull(sockets, "sockets"));
    }

    public Optional<BlendEntitySocket> socket(BlendResourceId key) {
        return Optional.ofNullable(sockets.get(Objects.requireNonNull(key, "key")));
    }

    /** Captures root/unit conversion and interpolated world origin during extraction, never during submit. */
    public static BlendEntitySockets capture(BlendEntitySnapshotRequest request, ClientSkinnedExtractionFrame frame) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(frame, "frame");
        var snapshot = frame.renderSnapshot();
        if (!request.modelKey().equals(snapshot.handle().modelKey())) {
            throw new IllegalArgumentException("Socket capture request must match the captured model key");
        }
        float units = snapshot.handle().unitsToBlocksScale();
        Transform rootUnits = snapshot.rootTransform().compose(
                new Transform(Vec3.ZERO, com.liy.blendlib.core.model.Quaternion.IDENTITY,
                        new Vec3(units, units, units)));
        Map<BlendResourceId, BlendEntitySocket> sockets = new LinkedHashMap<>();
        frame.socketTransforms().forEach((key, model) -> {
            Transform relative = rootUnits.compose(model);
            BlendEntitySocketPose local = pose(relative, 0, 0, 0);
            sockets.put(key, new BlendEntitySocket(pose(model, 0, 0, 0), local,
                    pose(relative, request.x(), request.y(), request.z()), units));
        });
        return new BlendEntitySockets(snapshot.generation(), sockets);
    }

    private static BlendEntitySocketPose pose(Transform value, double x, double y, double z) {
        var rotation = value.rotation();
        return new BlendEntitySocketPose(x + value.translation().x(), y + value.translation().y(),
                z + value.translation().z(), BlendEntityRotation.normalized(
                        rotation.x(), rotation.y(), rotation.z(), rotation.w()), value.scale().x());
    }
}
