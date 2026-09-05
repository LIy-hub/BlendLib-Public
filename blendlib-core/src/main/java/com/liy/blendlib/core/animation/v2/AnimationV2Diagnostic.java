package com.liy.blendlib.core.animation.v2;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Objects;

/** Immutable, renderer-independent v2 evaluation diagnostic. */
public record AnimationV2Diagnostic(
        AnimationV2DiagnosticCode code,
        BlendResourceId controllerId,
        BlendResourceId layerId,
        int boneIndex,
        String detail) {
    public AnimationV2Diagnostic {
        code = Objects.requireNonNull(code, "code");
        if (boneIndex < -1) {
            throw new IllegalArgumentException("boneIndex must be -1 or non-negative");
        }
        detail = Objects.requireNonNull(detail, "detail");
        if (detail.length() > 256) {
            throw new IllegalArgumentException("detail exceeds 256 UTF-16 code units");
        }
        if (controllerId != null) {
            AnimationV2Limits.requireCanonicalIdLength(controllerId.value(), "diagnostic controller id");
        }
        if (layerId != null) {
            AnimationV2Limits.requireCanonicalIdLength(layerId.value(), "diagnostic layer id");
        }
    }
}
