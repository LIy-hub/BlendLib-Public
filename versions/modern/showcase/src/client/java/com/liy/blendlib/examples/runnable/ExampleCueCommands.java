package com.liy.blendlib.examples.runnable;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.core.animation.v2.AnimationV2Command;
import java.util.List;
import java.util.Map;
import java.util.IdentityHashMap;

/** Extraction-thread identity capture, explicitly retired by entity-unload and disconnect callbacks. */
public final class ExampleCueCommands {
    private static final BlendAnimationKey ATTACK = BlendAnimationKey.parse(ExampleContent.MOD_ID + ":attack");
    // Values never retain their key. Changing the playhead under the same sequence is a conflict.
    private final Map<Object, CapturedCue> cues = new IdentityHashMap<>();
    private record CapturedCue(long generation, int sequence, AnimationV2Command command) { }

    public void retire(Object owner) { cues.remove(owner); }
    public void clear() { cues.clear(); }

    public List<AnimationV2Command> capture(Object owner, int sequence, long cueTick,
            double clientTicks, long generation) {
        if (sequence == 0) return List.of();
        CapturedCue cue = cues.get(owner);
        if (cue == null || cue.sequence() != sequence || cue.generation() != generation) {
            double seconds = Math.max(0, clientTicks - cueTick) / 20.0;
            cue = new CapturedCue(generation, sequence,
                    new AnimationV2Command(ExampleAnimationScene.UPPER, ATTACK, sequence, seconds, 1.0));
            cues.put(owner, cue);
        }
        return List.of(cue.command());
    }
}
