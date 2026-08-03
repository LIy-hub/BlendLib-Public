package com.liy.blendlib.core.model;

import java.util.List;
import java.util.Objects;

/** Immutable collection of skins decoded for a skinned model profile. */
public record Skeleton(List<Skin> skins) {
    public Skeleton {
        skins = List.copyOf(Objects.requireNonNull(skins, "skins"));
        if (skins.isEmpty()) {
            throw new IllegalArgumentException("A skeleton must contain at least one skin");
        }
    }
}
