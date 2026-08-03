package com.liy.blendlib.core.descriptor;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable optional descriptor animation state-machine declaration. */
public final class AnimationDefinition {
    private final BlendResourceId initialState;
    private final Map<BlendResourceId, AnimationStateDefinition> states;

    public AnimationDefinition(BlendResourceId initialState, Map<BlendResourceId, AnimationStateDefinition> states) {
        this.initialState = Objects.requireNonNull(initialState, "initialState");
        Objects.requireNonNull(states, "states");
        if (states.isEmpty() || !states.containsKey(initialState)) {
            throw new IllegalArgumentException("Animation states must contain initialState");
        }
        this.states = Collections.unmodifiableMap(new LinkedHashMap<>(states));
    }

    public BlendResourceId initialState() {
        return initialState;
    }

    public Map<BlendResourceId, AnimationStateDefinition> states() {
        return states;
    }
}
