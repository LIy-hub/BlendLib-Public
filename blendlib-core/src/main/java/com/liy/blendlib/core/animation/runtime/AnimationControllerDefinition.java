package com.liy.blendlib.core.animation.runtime;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.AnimationClip;
import com.liy.blendlib.core.descriptor.AnimationDefinition;
import com.liy.blendlib.core.descriptor.AnimationEventDefinition;
import com.liy.blendlib.core.descriptor.AnimationStateDefinition;
import com.liy.blendlib.core.limits.BlendAssetLimits;
import com.liy.blendlib.core.model.ModelAsset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable controller configuration compiled from frozen descriptor and clip data. */
public final class AnimationControllerDefinition {
    private final BlendAnimationKey initialState;
    private final Map<BlendAnimationKey, AnimationState> states;

    public AnimationControllerDefinition(BlendAnimationKey initialState, Map<BlendAnimationKey, AnimationState> states) {
        this.initialState = Objects.requireNonNull(initialState, "initialState");
        Objects.requireNonNull(states, "states");
        if (states.isEmpty() || !states.containsKey(initialState)) {
            throw new IllegalArgumentException("Animation states must contain the initial state");
        }
        if (states.size() > BlendAssetLimits.MAX_ANIMATION_STATES) {
            throw new IllegalArgumentException("Animation state limit exceeded");
        }
        LinkedHashMap<BlendAnimationKey, AnimationState> copied = new LinkedHashMap<>();
        int totalEventCount = 0;
        for (Map.Entry<BlendAnimationKey, AnimationState> entry : states.entrySet()) {
            BlendAnimationKey key = Objects.requireNonNull(entry.getKey(), "state key");
            AnimationState state = Objects.requireNonNull(entry.getValue(), "state");
            if (!key.equals(state.key())) {
                throw new IllegalArgumentException("Animation state map key must equal the state key: " + key);
            }
            totalEventCount += state.events().size();
            if (totalEventCount > BlendAssetLimits.MAX_VISUAL_EVENTS_PER_DESCRIPTOR) {
                throw new IllegalArgumentException("Animation descriptor visual-event limit exceeded");
            }
            copied.put(key, state);
        }
        for (AnimationState state : copied.values()) {
            if (state.next() != null && !copied.containsKey(state.next())) {
                throw new IllegalArgumentException("Animation next state is not declared: " + state.next());
            }
        }
        this.states = Collections.unmodifiableMap(copied);
    }

    /** Compiles descriptor state declarations and immutable clip data from one loaded asset generation. */
    public static AnimationControllerDefinition fromModelAsset(ModelAsset asset) {
        Objects.requireNonNull(asset, "asset");
        AnimationDefinition declaration = asset.animationDefinition();
        if (declaration == null) {
            throw new IllegalArgumentException("Model asset has no animation state declaration: " + asset.modelKey());
        }

        Map<String, AnimationClip> clipsByName = new LinkedHashMap<>();
        for (AnimationClip clip : asset.clips()) {
            if (clipsByName.putIfAbsent(clip.name(), clip) != null) {
                throw new IllegalArgumentException("Model asset contains duplicate clip name: " + clip.name());
            }
        }

        Map<BlendAnimationKey, AnimationState> compiled = new LinkedHashMap<>();
        for (Map.Entry<BlendResourceId, AnimationStateDefinition> entry : declaration.states().entrySet()) {
            BlendAnimationKey stateKey = BlendAnimationKey.fromResourceId(entry.getKey());
            AnimationStateDefinition state = entry.getValue();
            AnimationClip clip = clipsByName.get(state.clip());
            if (clip == null) {
                throw new IllegalArgumentException("Animation state references a missing clip: " + state.clip());
            }
            BlendAnimationKey next = state.nextState() == null ? null : BlendAnimationKey.fromResourceId(state.nextState());
            List<AnimationVisualEvent> events = state.events().stream()
                    .map(AnimationControllerDefinition::visualEvent)
                    .toList();
            compiled.put(stateKey, new AnimationState(
                    stateKey, clip, state.loop(), state.speed(), state.blendSeconds(), next, events));
        }
        return new AnimationControllerDefinition(BlendAnimationKey.fromResourceId(declaration.initialState()), compiled);
    }

    public BlendAnimationKey initialState() {
        return initialState;
    }

    public AnimationState initialStateDefinition() {
        return states.get(initialState);
    }

    public AnimationState state(BlendAnimationKey key) {
        AnimationState state = states.get(Objects.requireNonNull(key, "key"));
        if (state == null) {
            throw new IllegalArgumentException("Undeclared animation state: " + key);
        }
        return state;
    }

    public Map<BlendAnimationKey, AnimationState> states() {
        return states;
    }

    private static AnimationVisualEvent visualEvent(AnimationEventDefinition source) {
        return new AnimationVisualEvent(source.timeSeconds(), source.eventKey());
    }
}
