package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Objects;
import java.util.Optional;

/** Immutable part/bone selector that is resolved to prepared geometry before submit. */
public record X6LayerTargetSelector(Optional<BlendResourceId> partId, Optional<BlendResourceId> boneId) {
    public X6LayerTargetSelector {
        partId = Objects.requireNonNull(partId, "partId");
        boneId = Objects.requireNonNull(boneId, "boneId");
        partId.ifPresent(value -> X6Ids.requireId(value, "partId"));
        boneId.ifPresent(value -> X6Ids.requireId(value, "boneId"));
    }

    /** Creates a selector for one already prepared mesh/primitive part. */
    public static X6LayerTargetSelector part(BlendResourceId partId) {
        return new X6LayerTargetSelector(Optional.of(X6Ids.requireId(partId, "partId")), Optional.empty());
    }

    /** Creates a frozen part/bone selector; the bone id is diagnostic metadata, not a submit lookup key. */
    public static X6LayerTargetSelector partBone(BlendResourceId partId, BlendResourceId boneId) {
        return new X6LayerTargetSelector(
                Optional.of(X6Ids.requireId(partId, "partId")), Optional.of(X6Ids.requireId(boneId, "boneId")));
    }

    /** Attachment layers may target the prepared parent transform without a geometry part. */
    public static X6LayerTargetSelector wholeModel() {
        return new X6LayerTargetSelector(Optional.empty(), Optional.empty());
    }
}
