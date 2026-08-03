package com.liy.blendlib.core.animation.runtime;

import com.liy.blendlib.core.animation.AnimationClip;
import java.util.List;
import java.util.Objects;

/** Internal, immutable hot-path representation of one frozen loader clip. */
final class CompiledAnimationClip {
    private final List<CompiledAnimationChannel> channels;

    private CompiledAnimationClip(AnimationClip source) {
        this.channels = source.channels().stream().map(CompiledAnimationChannel::compile).toList();
    }

    static CompiledAnimationClip compile(AnimationClip source) {
        return new CompiledAnimationClip(Objects.requireNonNull(source, "source"));
    }

    List<CompiledAnimationChannel> channels() {
        return channels;
    }
}
