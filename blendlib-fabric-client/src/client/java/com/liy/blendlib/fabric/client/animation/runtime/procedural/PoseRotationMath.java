package com.liy.blendlib.fabric.client.animation.runtime.procedural;

import com.liy.blendlib.core.animation.runtime.LocalPose;
import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import com.liy.blendlib.fabric.client.animation.runtime.ClientAnimationRigView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

final class PoseRotationMath {
    private PoseRotationMath() { }

    static String nodeName(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("node name must not be blank");
        return name;
    }

    static float weight(float weight) {
        if (!Float.isFinite(weight) || weight < 0 || weight > 1) {
            throw new IllegalArgumentException("weight must be finite and in [0, 1]");
        }
        return weight;
    }

    static Quaternion inverse(Quaternion q) { return new Quaternion(-q.x(), -q.y(), -q.z(), q.w()).normalized(); }

    static LocalPose replace(LocalPose pose, Map<Integer, Quaternion> rotations) {
        Map<Integer, Transform> result = new LinkedHashMap<>(pose.transforms());
        rotations.forEach((index, rotation) -> {
            Transform previous = pose.transform(index);
            result.put(index, new Transform(previous.translation(), rotation, previous.scale()));
        });
        return new LocalPose(result);
    }

    static Transform global(LocalPose pose, ClientAnimationRigView rig, int node) {
        ArrayList<Integer> chain = new ArrayList<>();
        int current = node;
        while (true) {
            if (chain.size() >= rig.nodeCount()) throw new IllegalArgumentException("Cyclic rig hierarchy");
            chain.add(current);
            var parent = rig.parentIndex(current);
            if (parent.isEmpty()) break;
            current = parent.getAsInt();
        }
        Transform result = Transform.IDENTITY;
        for (int i = chain.size() - 1; i >= 0; i--) result = result.compose(pose.transform(chain.get(i)));
        return result;
    }

    static Quaternion between(Vec3 from, Vec3 to) {
        Vec3 a = from.normalized();
        Vec3 b = to.normalized();
        double dot = Math.max(-1, Math.min(1, a.dot(b)));
        if (dot < -0.999999) {
            Vec3 basis = Math.abs(a.x()) < 0.9f ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
            Vec3 axis = a.cross(basis).normalized();
            return new Quaternion(axis.x(), axis.y(), axis.z(), 0);
        }
        Vec3 cross = a.cross(b);
        return new Quaternion(cross.x(), cross.y(), cross.z(), (float) (1 + dot)).normalized();
    }

    // Principal rotation vector, radians, with equivalent quaternion signs canonicalized.
    static Vec3 log(Quaternion rotation) {
        Quaternion q = rotation.normalized();
        float sign = q.w() < 0 ? -1 : 1;
        double length = Math.sqrt((double) q.x()*q.x() + (double) q.y()*q.y() + (double) q.z()*q.z());
        if (length < 1e-9) return Vec3.ZERO;
        double scale = sign * 2 * Math.atan2(length, Math.abs(q.w())) / length;
        return new Vec3((float)(q.x()*scale), (float)(q.y()*scale), (float)(q.z()*scale));
    }

    static Quaternion exp(Vec3 v) {
        double angle = Math.sqrt((double)v.x()*v.x() + (double)v.y()*v.y() + (double)v.z()*v.z());
        if (angle < 1e-9) return Quaternion.IDENTITY;
        double scale = Math.sin(angle / 2) / angle;
        return new Quaternion((float)(v.x()*scale), (float)(v.y()*scale), (float)(v.z()*scale),
                (float)Math.cos(angle / 2)).normalized();
    }
}
