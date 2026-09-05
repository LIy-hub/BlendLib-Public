package com.liy.blendlib.core.procedural;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Objects;

/** Immutable external command attribution; stable order is priority then canonical source id. */
public record ProceduralDirective(BlendResourceId sourceId, int priority, ProceduralOperation operation) {
    public ProceduralDirective {
        ProceduralSupport.requireId(sourceId, "directive source id");
        operation = Objects.requireNonNull(operation, "operation");
    }
}
