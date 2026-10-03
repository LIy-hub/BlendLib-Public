package com.liy.blendlib.core.animation.runtime;

import com.liy.blendlib.core.animation.AnimationClip;
import java.util.List;
import java.util.Objects;

/** Internal, immutable hot-path representation of one frozen loader clip. */
final class CompiledAnimationClip {
    private final List<CompiledAnimationChannel> channels;
    private final java.util.Map<Integer, List<CompiledAnimationChannel>> channelsByNode;

    private CompiledAnimationClip(AnimationClip source) {
        this.channels = source.channels().stream().map(CompiledAnimationChannel::compile).toList();
        java.util.Map<Integer, java.util.ArrayList<CompiledAnimationChannel>> grouped = new java.util.HashMap<>();
        for (var channel : channels) grouped.computeIfAbsent(channel.targetNode(), ignored -> new java.util.ArrayList<>()).add(channel);
        java.util.Map<Integer, List<CompiledAnimationChannel>> frozen = new java.util.HashMap<>();
        grouped.forEach((node, values) -> frozen.put(node, List.copyOf(values)));
        this.channelsByNode = java.util.Map.copyOf(frozen);
    }

    static CompiledAnimationClip compile(AnimationClip source) {
        return new CompiledAnimationClip(Objects.requireNonNull(source, "source"));
    }

    List<CompiledAnimationChannel> channelsForNode(int nodeIndex) {
        return channelsByNode.getOrDefault(nodeIndex, List.of());
    }

    List<CompiledAnimationChannel> channels() {
        return channels;
    }
}
