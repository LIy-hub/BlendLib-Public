package com.liy.blendlib.core.animation.v2;

import com.liy.blendlib.api.BlendResourceId;
import java.util.ArrayList;
import java.util.List;

/** Per-evaluation bounded diagnostic collector. */
final class AnimationV2Diagnostics {
    private final List<AnimationV2Diagnostic> entries = new ArrayList<>();
    private boolean truncated;

    void add(AnimationV2DiagnosticCode code, BlendResourceId controllerId, BlendResourceId layerId, int boneIndex, String detail) {
        if (entries.size() < AnimationV2Limits.MAX_DIAGNOSTICS_PER_EVALUATION) {
            entries.add(new AnimationV2Diagnostic(code, controllerId, layerId, boneIndex, detail));
            return;
        }
        truncated = true;
    }

    List<AnimationV2Diagnostic> freeze() {
        if (truncated && !entries.isEmpty()) {
            AnimationV2Diagnostic last = entries.getLast();
            entries.set(entries.size() - 1, new AnimationV2Diagnostic(
                    AnimationV2DiagnosticCode.DIAGNOSTICS_TRUNCATED,
                    last.controllerId(), last.layerId(), -1, "additional diagnostics were bounded"));
        }
        return List.copyOf(entries);
    }
}
