package com.liy.blendlib.fabric.client.animation.runtime.procedural;

import com.liy.blendlib.core.animation.runtime.LocalPose;
import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.fabric.client.animation.runtime.ClientAnimationPoseContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Blends each follower toward its preceding node's incoming LOCAL rotation; requires matching local axes. */
public final class ChainFollowPoseComponent implements ProceduralPoseComponent {
    private final List<String> nodes;
    private final float weight;

    public ChainFollowPoseComponent(List<String> orderedNodes, float weight) {
        this.nodes = List.copyOf(orderedNodes);
        if (nodes.size() < 2 || nodes.stream().distinct().count() != nodes.size()) {
            throw new IllegalArgumentException("A chain needs at least two distinct node names");
        }
        nodes.forEach(PoseRotationMath::nodeName);
        this.weight = PoseRotationMath.weight(weight);
    }

    @Override
    public LocalPose modify(ClientAnimationPoseContext context, LocalPose basePose) {
        int previous = context.rig().requireNodeIndex(nodes.getFirst());
        Map<Integer, Quaternion> rotations = new LinkedHashMap<>();
        for (int i = 1; i < nodes.size(); i++) {
            int node = context.rig().requireNodeIndex(nodes.get(i));
            if (context.rig().parentIndex(node).orElse(-1) != previous) {
                throw new IllegalArgumentException("Chain nodes must form a direct parent-child path");
            }
            rotations.put(node, Quaternion.slerp(basePose.transform(node).rotation(),
                    basePose.transform(previous).rotation(), weight));
            previous = node;
        }
        return weight == 0 ? basePose : PoseRotationMath.replace(basePose, rotations);
    }
}
