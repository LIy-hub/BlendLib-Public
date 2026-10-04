package com.liy.blendlib.fabric.client.animation.runtime.procedural;

import com.liy.blendlib.core.model.Vec3;
import java.util.Objects;

/** One immutable target/pole snapshot, both positions in the animated model's asset space. */
public record TwoBoneIkTarget(Vec3 targetModelSpace, Vec3 poleModelSpace) {
    public TwoBoneIkTarget {
        Objects.requireNonNull(targetModelSpace, "targetModelSpace");
        Objects.requireNonNull(poleModelSpace, "poleModelSpace");
    }
}
