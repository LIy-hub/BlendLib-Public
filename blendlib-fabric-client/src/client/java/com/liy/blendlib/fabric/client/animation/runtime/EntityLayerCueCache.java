package com.liy.blendlib.fabric.client.animation.runtime;

import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.v2.AnimationV2Command;
import com.liy.blendlib.fabric.client.entity.BlendEntityLayerCue;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Single extraction-owner cache. Entity equality deliberately never participates in lookup. */
final class EntityLayerCueCache {
    private final Map<Object, Map<Object, Capture>> owners = new IdentityHashMap<>();
    private record Capture(BlendInstanceKey.Entity instance, BlendModelKey model, long generation,
            Map<BlendResourceId, AnimationV2Command> commands) { }

    List<AnimationV2Command> capture(Object source, Object owner, BlendInstanceKey.Entity instance,
            BlendModelKey model, long generation, double clientTicks, List<BlendEntityLayerCue> cues) {
        return capture(source, owner, instance, model, generation, clientTicks, cues, ignored -> 1.0);
    }

    List<AnimationV2Command> capture(Object source, Object owner, BlendInstanceKey.Entity instance,
            BlendModelKey model, long generation, double clientTicks, List<BlendEntityLayerCue> cues,
            java.util.function.ToDoubleFunction<BlendEntityLayerCue> descriptorSpeed) {
        Objects.requireNonNull(descriptorSpeed, "descriptorSpeed");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(model, "model");
        if (generation < 0 || !Double.isFinite(clientTicks)) throw new IllegalArgumentException("Invalid cue clock/generation");
        var checked = List.copyOf(cues);
        var controllers = new HashSet<BlendResourceId>();
        for (var cue : checked) {
            if (!controllers.add(cue.controllerId())) throw new IllegalArgumentException("Duplicate cue controller: " + cue.controllerId());
        }
        if (checked.isEmpty()) return List.of();
        var sources = owners.computeIfAbsent(owner, ignored -> new IdentityHashMap<>());
        var capture = sources.get(source);
        if (capture == null || !capture.instance().equals(instance) || !capture.model().equals(model)
                || capture.generation() != generation) {
            capture = new Capture(instance, model, generation, new HashMap<>());
            sources.put(source, capture);
        }
        var result = new ArrayList<AnimationV2Command>(checked.size());
        for (var cue : checked) {
            var previous = capture.commands().get(cue.controllerId());
            if (previous != null && cue.sequence() < previous.sequence()) continue;
            if (previous == null || cue.sequence() > previous.sequence()) {
                double seconds = Math.max(0.0, clientTicks - (double) cue.startTick()) / 20.0 * cue.playbackSpeed() * descriptorSpeed.applyAsDouble(cue);
                previous = new AnimationV2Command(cue.controllerId(), cue.animationKey(), cue.sequence(), seconds, cue.playbackSpeed());
                capture.commands().put(cue.controllerId(), previous);
            }
            result.add(previous);
        }
        return List.copyOf(result);
    }

    void retireEntity(int entityId) {
        owners.values().forEach(sources -> sources.values().removeIf(c -> c.instance().entityId() == entityId));
        owners.values().removeIf(Map::isEmpty);
    }

    void retire(BlendInstanceKey instance) {
        owners.values().forEach(sources -> sources.values().removeIf(c -> c.instance().equals(instance)));
        owners.values().removeIf(Map::isEmpty);
    }

    void retainGeneration(long generation) {
        owners.values().forEach(sources -> sources.values().removeIf(c -> c.generation() != generation));
        owners.values().removeIf(Map::isEmpty);
    }

    void clear() { owners.clear(); }
    int ownerCount() { return owners.size(); }
}
