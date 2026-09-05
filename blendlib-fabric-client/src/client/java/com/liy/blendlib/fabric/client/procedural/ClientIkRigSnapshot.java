package com.liy.blendlib.fabric.client.procedural;

import com.liy.blendlib.api.ModelInstance;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import com.liy.blendlib.core.procedural.ProceduralFrameSnapshot;
import com.liy.blendlib.core.procedural.ProceduralRigPlan;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Client-only, immutable IK input captured from one validated X3 plan/snapshot pair.
 *
 * <p>It deliberately derives hierarchy and parent transforms from the frozen rig plan; callers cannot inject
 * alternative transforms or claim an unrelated three-bone chain.</p>
 */
public final class ClientIkRigSnapshot {
    private final ModelInstance modelInstance;
    private final List<Transform> modelTransforms;
    private final int[] parents;

    private ClientIkRigSnapshot(ModelInstance modelInstance, List<Transform> modelTransforms, int[] parents) {
        this.modelInstance = modelInstance;
        this.modelTransforms = modelTransforms;
        this.parents = parents;
    }

    public static ClientIkRigSnapshot capture(ProceduralRigPlan plan, ProceduralFrameSnapshot snapshot) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(snapshot, "snapshot");
        ModelInstance instance = snapshot.modelInstance();
        if (!instance.modelKey().equals(plan.modelKey()) || instance.resourceGeneration() != plan.generation()) {
            throw new IllegalArgumentException("IK snapshot scope does not match the frozen rig plan");
        }
        if (!snapshot.matchesPlan(plan)) {
            throw new IllegalArgumentException("IK snapshot did not originate from this exact frozen rig plan");
        }
        if (snapshot.modelTransforms().size() != plan.schema().boneCount()) {
            throw new IllegalArgumentException("IK snapshot cardinality does not match the frozen rig plan");
        }
        int[] parents = new int[plan.schema().boneCount()];
        for (int index = 0; index < parents.length; index++) {
            parents[index] = plan.parentOf(index);
        }
        return new ClientIkRigSnapshot(instance, List.copyOf(new ArrayList<>(snapshot.modelTransforms())), parents);
    }

    public ModelInstance modelInstance() {
        return modelInstance;
    }

    public int boneCount() {
        return parents.length;
    }

    boolean containsBone(int boneIndex) {
        return boneIndex >= 0 && boneIndex < parents.length;
    }

    boolean isDirectTwoBoneChain(int rootBone, int middleBone, int endBone) {
        return containsBone(rootBone) && containsBone(middleBone) && containsBone(endBone)
                && parents[middleBone] == rootBone && parents[endBone] == middleBone;
    }

    Transform modelTransform(int boneIndex) {
        requireBone(boneIndex);
        return modelTransforms.get(boneIndex);
    }

    Transform parentModelTransform(int boneIndex) {
        requireBone(boneIndex);
        int parent = parents[boneIndex];
        return parent == -1 ? Transform.IDENTITY : modelTransforms.get(parent);
    }

    Vec3 rootTranslationOrZero(int boneIndex) {
        return containsBone(boneIndex) ? modelTransforms.get(boneIndex).translation() : Vec3.ZERO;
    }

    private void requireBone(int boneIndex) {
        if (!containsBone(boneIndex)) {
            throw new IllegalArgumentException("IK bone index is outside the frozen rig snapshot");
        }
    }
}
