package com.liy.blendlib.core.descriptor;

import com.liy.blendlib.core.model.MorphTargetSet;
import java.util.Objects;

/** Exact node-path / target-name binding and a declared signed runtime interval. */
public record MorphControlDefinition(String node, String target, float minWeight, float maxWeight) {
    public MorphControlDefinition {
        Objects.requireNonNull(node, "node");
        if (node.isBlank() || node.length() > 1024 || node.startsWith("/") || node.endsWith("/") || node.contains("//")
                || node.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid morph node path");
        MorphTargetSet.validateTargetName(target);
        if (!Float.isFinite(minWeight) || !Float.isFinite(maxWeight) || minWeight < -2 || minWeight > 0 || maxWeight < 0 || maxWeight > 2) {
            throw new IllegalArgumentException("Morph interval must include zero and lie within [-2,2]");
        }
    }
}
