package com.liy.blendlib.core.animation.v2;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendResourceId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable one-controller v2 plan. It is internal/experimental and does not alter v1 controller behavior. */
public final class AnimationV2ControllerDefinition {
    private final BlendResourceId id;
    private final int priority;
    private final List<AnimationV2LayerDefinition> layers;
    private final Map<BlendResourceId, AnimationV2LayerDefinition> layersById;
    private final BlendAnimationKey initialState;
    private final Map<BlendAnimationKey, AnimationV2ControllerState> states;
    private final Map<BlendAnimationKey, AnimationV2Clip[]> clipsByStateInLayerOrder;
    private final List<AnimationV2Diagnostic> staticDiagnostics;

    public AnimationV2ControllerDefinition(
            BlendResourceId id,
            int priority,
            List<AnimationV2LayerDefinition> layers,
            BlendAnimationKey initialState,
            Map<BlendAnimationKey, AnimationV2ControllerState> states) {
        this.id = Objects.requireNonNull(id, "id");
        AnimationV2Limits.requireCanonicalIdLength(id.value(), "controller id");
        AnimationV2Limits.requirePriority(priority, "controller priority");
        this.priority = priority;
        Objects.requireNonNull(layers, "layers");
        if (layers.isEmpty() || layers.size() > AnimationV2Limits.MAX_LAYERS_PER_CONTROLLER) {
            throw new IllegalArgumentException("layer count is outside supported bounds");
        }
        LinkedHashMap<BlendResourceId, AnimationV2LayerDefinition> copiedLayers = new LinkedHashMap<>();
        int boneCount = -1;
        for (AnimationV2LayerDefinition layer : layers) {
            AnimationV2LayerDefinition checked = Objects.requireNonNull(layer, "layer");
            if (copiedLayers.putIfAbsent(checked.id(), checked) != null) {
                throw new IllegalArgumentException("duplicate controller layer id: " + checked.id());
            }
            if (boneCount == -1) {
                boneCount = checked.mask().boneCount();
            } else if (boneCount != checked.mask().boneCount()) {
                throw new IllegalArgumentException("all controller layer masks must use the same bone schema size");
            }
        }
        List<AnimationV2LayerDefinition> orderedLayers = new ArrayList<>(copiedLayers.values());
        orderedLayers.sort(Comparator.comparingInt(AnimationV2LayerDefinition::priority)
                .thenComparing(layer -> layer.id().value()));
        this.layers = List.copyOf(orderedLayers);
        this.layersById = Collections.unmodifiableMap(copiedLayers);
        List<AnimationV2Diagnostic> diagnostics = new ArrayList<>();
        for (AnimationV2LayerDefinition layer : orderedLayers) {
            if (layer.mask().isEmpty()) {
                diagnostics.add(new AnimationV2Diagnostic(AnimationV2DiagnosticCode.EMPTY_MASK,
                        id, layer.id(), -1, "layer is a deterministic no-op"));
            }
            if (layer.weight() == 0.0F) {
                diagnostics.add(new AnimationV2Diagnostic(AnimationV2DiagnosticCode.ZERO_WEIGHT_LAYER,
                        id, layer.id(), -1, "layer is a deterministic no-op"));
            }
        }

        this.initialState = Objects.requireNonNull(initialState, "initialState");
        AnimationV2Limits.requireCanonicalIdLength(this.initialState.value(), "initial state key");
        Objects.requireNonNull(states, "states");
        if (states.isEmpty() || states.size() > AnimationV2Limits.MAX_STATES_PER_CONTROLLER) {
            throw new IllegalArgumentException("states must contain the initial state and stay within the v2 bound");
        }
        for (BlendAnimationKey key : states.keySet()) {
            if (key != null) {
                AnimationV2Limits.requireCanonicalIdLength(key.value(), "state map key");
            }
        }
        if (!states.containsKey(initialState)) {
            throw new IllegalArgumentException("states must contain the initial state and stay within the v2 bound");
        }
        LinkedHashMap<BlendAnimationKey, AnimationV2ControllerState> copiedStates = new LinkedHashMap<>();
        for (Map.Entry<BlendAnimationKey, AnimationV2ControllerState> entry : states.entrySet()) {
            BlendAnimationKey key = Objects.requireNonNull(entry.getKey(), "state key");
            AnimationV2ControllerState state = Objects.requireNonNull(entry.getValue(), "state");
            if (!key.equals(state.key())) {
                throw new IllegalArgumentException("state map key must equal state key: " + key);
            }
            if (!state.clipsByLayer().keySet().equals(copiedLayers.keySet())) {
                throw new IllegalArgumentException("each state must declare exactly the controller layer ids");
            }
            for (AnimationV2Clip clip : state.clipsByLayer().values()) {
                if (clip.boneCount() != boneCount) {
                    throw new IllegalArgumentException("state clip bone count does not match controller masks");
                }
            }
            copiedStates.put(key, state);
        }
        for (AnimationV2ControllerState state : copiedStates.values()) {
            if (state.next() != null && !copiedStates.containsKey(state.next())) {
                throw new IllegalArgumentException("state next key is undeclared: " + state.next());
            }
        }
        this.states = Collections.unmodifiableMap(copiedStates);
        LinkedHashMap<BlendAnimationKey, AnimationV2Clip[]> orderedClips = new LinkedHashMap<>();
        for (Map.Entry<BlendAnimationKey, AnimationV2ControllerState> entry : copiedStates.entrySet()) {
            AnimationV2Clip[] clips = new AnimationV2Clip[this.layers.size()];
            for (int index = 0; index < clips.length; index++) {
                clips[index] = entry.getValue().clip(this.layers.get(index).id());
            }
            orderedClips.put(entry.getKey(), clips);
        }
        this.clipsByStateInLayerOrder = Collections.unmodifiableMap(orderedClips);
        this.staticDiagnostics = List.copyOf(diagnostics);
    }

    public BlendResourceId id() {
        return id;
    }

    public int priority() {
        return priority;
    }

    /** Layers are in deterministic application order: priority ascending then canonical id ascending. */
    public List<AnimationV2LayerDefinition> layers() {
        return layers;
    }

    public BlendAnimationKey initialState() {
        return initialState;
    }

    public AnimationV2ControllerState initialStateDefinition() {
        return states.get(initialState);
    }

    public AnimationV2ControllerState state(BlendAnimationKey key) {
        AnimationV2ControllerState state = states.get(Objects.requireNonNull(key, "key"));
        if (state == null) {
            throw new IllegalArgumentException("undeclared v2 controller state: " + key);
        }
        return state;
    }

    public Map<BlendAnimationKey, AnimationV2ControllerState> states() {
        return states;
    }

    public List<AnimationV2Diagnostic> staticDiagnostics() {
        return staticDiagnostics;
    }

    int layerCount() {
        return layers.size();
    }

    AnimationV2Clip[] clipsFor(AnimationV2ControllerState state) {
        AnimationV2Clip[] clips = clipsByStateInLayerOrder.get(Objects.requireNonNull(state, "state").key());
        if (clips == null) {
            throw new IllegalArgumentException("state is not declared by this controller: " + state.key());
        }
        return clips;
    }
}
