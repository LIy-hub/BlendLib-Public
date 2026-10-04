package com.liy.blendlib.examples.runnable;

import com.liy.blendlib.core.animation.v2.AnimationV2EvaluationSnapshot;
import com.liy.blendlib.core.animation.v2.ModelAnimationLayers;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Copyable, Minecraft-free presentation adapter over existing immutable BlendLib observations.
 * The supplied layer declarations describe configuration, not measured final bone influence.
 * No runtime, entity, callback, or snapshot is retained and formatting never advances playback.
 */
public final class ExampleLayerInspection {
    private ExampleLayerInspection() { }

    /** Formats a bounded discovery list; never chooses an actor on the caller's behalf. */
    public static List<String> targets(List<Integer> actorIds) {
        var ids = List.copyOf(actorIds).stream().distinct().sorted().toList();
        if (ids.isEmpty()) return List.of("No loaded example actors in the 16-block search box around you");
        List<String> lines = new ArrayList<>();
        lines.add("Loaded example actors in the 16-block search box: " + ids.size());
        ids.stream().limit(8).forEach(id -> lines.add("  /blendlib_example inspect " + id));
        if (ids.size() > 8) lines.add("  Additional actors omitted; move closer to narrow the list");
        return List.copyOf(lines);
    }

    public static List<String> format(List<ModelAnimationLayers.Layer> layers,
            AnimationV2EvaluationSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        List<String> lines = new ArrayList<>();
        lines.add("Last sampled layer revision=" + snapshot.revision()
                + " (may lag while culled; read-only, no playback advance)");
        for (var layer : List.copyOf(layers)) {
            String mask = layer.bones().isEmpty() ? "all bones" : layer.bones().stream()
                    .map(bone -> bone.boneName() + "=" + number(bone.weight()))
                    .collect(Collectors.joining(", "));
            lines.add("Configured " + layer.id() + " priority=" + layer.priority() + " mode=" + layer.mode()
                    + " weight=" + number(layer.weight()) + " mask=[" + mask + "]");
            var effective = snapshot.effectiveLayerWeights().get(
                    new com.liy.blendlib.core.animation.v2.AnimationV2LayerWeights.Key(layer.id(), layer.id()));
            lines.add("  Sampled effectiveWeight=" + (effective == null ? "unavailable" : number(effective))
                    + " (before bone mask and priority resolution)");
            var playhead = snapshot.playheads().get(layer.id());
            if (playhead == null) {
                lines.add("  No sampled playhead for this configured layer");
            } else {
                lines.add("  Sampled state=" + playhead.state() + " clipSeconds=" + number(playhead.timeSeconds())
                        + " acceptedSequence=" + playhead.acceptedSequence()
                        + " previousState=" + (playhead.previousState() == null ? "none" : playhead.previousState())
                        + " transition=" + number(playhead.transitionProgress()));
            }
        }
        lines.add("Sampled diagnostics=" + snapshot.diagnostics().size());
        snapshot.diagnostics().stream().limit(8).forEach(diagnostic -> lines.add("  " + diagnostic.code()
                + " controller=" + diagnostic.controllerId() + " layer=" + diagnostic.layerId()
                + " bone=" + diagnostic.boneIndex() + " " + diagnostic.detail()));
        if (snapshot.diagnostics().size() > 8) lines.add("  Additional diagnostics omitted from chat");
        return List.copyOf(lines);
    }

    /** Derives a labelled normalized observation without sampling or reading model resources. */
    public static List<String> formatBlendSpace(AnimationV2EvaluationSnapshot snapshot,
            java.util.Map<com.liy.blendlib.api.BlendResourceId, Double> clipDurations) {
        Objects.requireNonNull(snapshot, "snapshot");
        List<String> lines = new ArrayList<>();
        lines.add("Sampled blendspace normalized playheads (clipSeconds / current loaded clip duration; read-only)");
        for (var layer : ExampleBlendSpaceScene.layers().subList(0, 3)) {
            var playhead = snapshot.playheads().get(layer.id());
            Double duration = clipDurations.get(layer.id());
            if (duration == null || !Double.isFinite(duration) || duration <= 0
                    || playhead == null || !playhead.state().equals(layer.initialState())) {
                lines.add("  " + layer.id() + " normalizedPlayhead=unavailable");
            } else {
                double phase = playhead.timeSeconds() / duration;
                lines.add("  " + layer.id() + " normalizedPlayhead=" + number(phase - Math.floor(phase))
                        + " durationSeconds=" + number(duration));
            }
        }
        return List.copyOf(lines);
    }

    private static String number(double value) { return String.format(Locale.ROOT, "%.3f", value); }
}
