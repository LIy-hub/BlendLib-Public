package com.liy.blendlib.fabric.client.procedural;

import com.liy.blendlib.api.BlendResourceId;

/** Explicit opt-in, presentation-only X3 client IK extension point. */
public interface ExperimentalClientIkSolver {
    BlendResourceId id();

    ClientIkResult solve(ClientIkRequest request);
}
