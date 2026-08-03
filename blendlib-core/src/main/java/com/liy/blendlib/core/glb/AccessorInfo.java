package com.liy.blendlib.core.glb;

import java.util.List;
import java.util.Objects;

/** Fully bounds-checked accessor layout derived from one GLB buffer view. */
public record AccessorInfo(
        int index,
        int count,
        String type,
        int componentType,
        boolean normalized,
        int componentCount,
        int componentByteSize,
        int elementByteSize,
        int byteStride,
        int firstByteOffset,
        List<Double> minimumValues,
        List<Double> maximumValues) {

    public AccessorInfo {
        minimumValues = List.copyOf(Objects.requireNonNull(minimumValues, "minimumValues"));
        maximumValues = List.copyOf(Objects.requireNonNull(maximumValues, "maximumValues"));
    }

    /** Whether both glTF accessor bound arrays were declared and validated. */
    public boolean hasMinMax() {
        return !minimumValues.isEmpty() && !maximumValues.isEmpty();
    }
}
