package com.liy.blendlib.fabric.client.animation.runtime.procedural;

import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.core.animation.runtime.LocalPose;
import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.Vec3;
import com.liy.blendlib.fabric.client.animation.runtime.ClientAnimationPoseContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Critically damped local-rotation inertia, driven by client game time (20 ticks/second).
 * State is bounded and isolated per instance/model/generation. Repeated extraction at the same
 * time does not advance the spring; backward time or a gap over 0.5 seconds snaps to the input.
 * Explicitly reset on teleports/removal. Methods are synchronized; target poses are never mutated.
 */
public final class SpringInertiaPoseComponent implements ProceduralPoseComponent {
    private final String nodeName;
    private final double angularFrequency;
    private final int maximumInstances;
    private final LinkedHashMap<BlendInstanceKey, State> states = new LinkedHashMap<>(16, 0.75f, true);

    /** @param frequencyHz natural frequency in (0, 1000]; higher values respond faster
     * @param maximumInstances positive LRU state capacity; an evicted instance restarts at its input
     */
    public SpringInertiaPoseComponent(String nodeName, double frequencyHz, int maximumInstances) {
        this.nodeName = PoseRotationMath.nodeName(nodeName);
        if (!Double.isFinite(frequencyHz) || frequencyHz <= 0 || frequencyHz > 1000) {
            throw new IllegalArgumentException("frequencyHz must be finite and in (0, 1000]");
        }
        if (maximumInstances <= 0) throw new IllegalArgumentException("maximumInstances must be positive");
        this.angularFrequency = 2 * Math.PI * frequencyHz;
        this.maximumInstances = maximumInstances;
    }

    @Override
    public synchronized LocalPose modify(ClientAnimationPoseContext context, LocalPose basePose) {
        int node = context.rig().requireNodeIndex(nodeName);
        Quaternion goal = basePose.transform(node).rotation();
        double time = context.clientGameTimeInTicks() / 20;
        State previous = states.get(context.instanceKey());
        double dt = previous == null ? 0 : time - previous.time;
        if (previous == null || !previous.model.equals(context.modelKey())
                || previous.generation != context.generation() || previous.node != node || dt < 0 || dt > 0.5) {
            put(context, new State(context.modelKey(), context.generation(), node, time, goal, Vec3.ZERO));
            return basePose;
        }
        if (dt == 0) return PoseRotationMath.replace(basePose, Map.of(node, previous.rotation));

        // Integrate the principal relative rotation vector analytically for a fixed target during dt.
        // Velocity is retained in parent-local axes, so changing targets preserve inertia.
        Vec3 error = PoseRotationMath.log(previous.rotation.multiply(PoseRotationMath.inverse(goal)));
        double decay = Math.exp(-angularFrequency * dt);
        double[] x = {error.x(), error.y(), error.z()};
        double[] v = {previous.velocity.x(), previous.velocity.y(), previous.velocity.z()};
        for (int axis = 0; axis < 3; axis++) {
            double c = v[axis] + angularFrequency * x[axis];
            v[axis] = (v[axis] - angularFrequency * c * dt) * decay;
            x[axis] = (x[axis] + c * dt) * decay;
        }
        Quaternion rotation = PoseRotationMath.exp(new Vec3((float)x[0], (float)x[1], (float)x[2])).multiply(goal);
        Vec3 velocity = new Vec3((float)v[0], (float)v[1], (float)v[2]);
        put(context, new State(context.modelKey(), context.generation(), node, time, rotation, velocity));
        return PoseRotationMath.replace(basePose, Map.of(node, rotation));
    }

    private void put(ClientAnimationPoseContext context, State state) {
        states.put(context.instanceKey(), state);
        if (states.size() > maximumInstances) states.remove(states.keySet().iterator().next());
    }

    @Override public synchronized void reset() { states.clear(); }
    @Override public synchronized void reset(BlendInstanceKey key) { states.remove(Objects.requireNonNull(key, "key")); }

    /** Number of retained instances, useful for lifecycle diagnostics. */
    public synchronized int retainedInstanceCount() { return states.size(); }

    private record State(BlendModelKey model, long generation, int node, double time,
            Quaternion rotation, Vec3 velocity) { }
}
