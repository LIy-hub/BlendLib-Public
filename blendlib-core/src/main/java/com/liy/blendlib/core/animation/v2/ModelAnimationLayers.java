package com.liy.blendlib.core.animation.v2;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.runtime.AnimationControllerDefinition;
import com.liy.blendlib.core.animation.runtime.AnimationVisualEvent;
import com.liy.blendlib.core.animation.runtime.LocalPose;
import com.liy.blendlib.core.animation.runtime.PoseSampler;
import com.liy.blendlib.core.model.ModelAsset;
import java.util.*;

/** Generation-scoped bridge from loaded descriptor clips to the layered evaluator. */
public final class ModelAnimationLayers {
    private final AnimationV2InstancePlan plan;
    private final List<Integer> nodeIndices;
    private final Map<BlendAnimationKey, List<AnimationVisualEvent>> visualEvents;

    /** Each layer is independently controlled; empty bone weights mean the entire model. */
    public record Layer(BlendResourceId id, int priority, AnimationV2LayerMode mode,
                        float weight, List<BoneMask.NamedWeight> bones, BlendAnimationKey initialState) {
        public Layer {
            Objects.requireNonNull(id, "id"); Objects.requireNonNull(mode, "mode");
            bones = List.copyOf(bones); Objects.requireNonNull(initialState, "initialState");
        }
    }

    public ModelAnimationLayers(ModelAsset asset, List<Layer> layers) {
        Objects.requireNonNull(asset, "asset");
        var nodes = asset.nodes();
        nodeIndices = nodes.stream().map(n -> n.index()).toList();
        // Unique short names are intentional: ambiguous names must be resolved by the asset author.
        BoneSchema schema = new BoneSchema(nodes.stream().map(n -> n.name()).toList(),
                nodes.stream().map(n -> n.localTransform()).toList());
        var definition = AnimationControllerDefinition.fromModelAsset(asset);
        Map<BlendAnimationKey, List<AnimationVisualEvent>> events = new LinkedHashMap<>();
        definition.states().forEach((key, state) -> events.put(key, state.events()));
        visualEvents = Collections.unmodifiableMap(events);
        var sampler = PoseSampler.fromModelAsset(asset);
        Map<BlendAnimationKey, AnimationV2Clip> clips = new LinkedHashMap<>();
        definition.states().forEach((key, state) -> clips.put(key, AnimationV2Clip.fromState(sampler, state, nodeIndices)));
        List<AnimationV2ControllerDefinition> controllers = new ArrayList<>();
        for (Layer layer : List.copyOf(layers)) {
            BoneMask mask = layer.bones().isEmpty() ? BoneMask.all(schema) : BoneMask.named(schema, layer.bones());
            var meta = new AnimationV2LayerDefinition(layer.id(), 0, layer.mode(), layer.weight(), mask, false);
            Map<BlendAnimationKey, AnimationV2ControllerState> states = new LinkedHashMap<>();
            definition.states().forEach((key, state) -> states.put(key, new AnimationV2ControllerState(key,
                    state.loop() ? AnimationV2PlaybackMode.LOOP : AnimationV2PlaybackMode.ONCE,
                    state.speed(), state.blendSeconds(), state.next(), Map.of(layer.id(), clips.get(key)))));
            controllers.add(new AnimationV2ControllerDefinition(layer.id(), layer.priority(), List.of(meta),
                    layer.initialState(), states));
        }
        plan = new AnimationV2InstancePlan(schema, controllers);
    }

    public AnimationV2InstancePlan plan() { return plan; }

    /**
     * Creates an instance-local descriptor event observer for this exact generation-scoped plan.
     * The cursor must be consumed on every extraction, including frames without a callback handler.
     */
    public LayerAnimationVisualEventCursor newVisualEventCursor(AnimationV2InstanceRuntime runtime) {
        return new LayerAnimationVisualEventCursor(runtime, plan, visualEvents);
    }

    public LocalPose localPose(AnimationV2Pose pose) {
        if (pose.boneCount() != nodeIndices.size()) throw new IllegalArgumentException("Mismatched pose domain");
        Map<Integer, com.liy.blendlib.core.model.Transform> transforms = new LinkedHashMap<>();
        for (int i = 0; i < nodeIndices.size(); i++) transforms.put(nodeIndices.get(i), pose.transform(i));
        return new LocalPose(transforms);
    }
}
