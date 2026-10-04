package com.liy.blendlib.examples.runnable;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.runtime.MorphFrameOverrides;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Object-owned, client-local presentation choices. No saved data or network protocol. */
public final class ExampleCpuMorphControls {
    public static final BlendModelKey MODEL = BlendModelKey.parse("cpu_morph:face_actor");
    public static final BlendAnimationKey NOD = BlendAnimationKey.parse("cpu_morph:nod");
    public static final BlendResourceId FACE = BlendResourceId.parse("cpu_morph:face");
    public static final List<String> CLIPS = List.of("nod", "blink", "smile", "breath");
    public static final List<String> CONTROLS = List.of("blink", "smile", "breath");
    private final Map<BlendResourceId, Float> requested = new LinkedHashMap<>();
    private BlendAnimationKey animation = NOD;
    private long generation = -1;
    private String session;
    private long visualEvents;
    private BlendResourceId lastEvent;

    /** First use after reload/reconnect resets choices before either frame callback reads them. */
    public ExampleCpuMorphControls at(long generation, String session) {
        if (generation < 0 || Objects.requireNonNull(session, "session").isBlank())
            throw new IllegalArgumentException("A live model generation and connection session are required");
        if (this.generation != generation || !session.equals(this.session)) {
            clear();
            this.generation = generation;
            this.session = session;
        }
        return this;
    }

    /** Validate the entire consumer edit before changing its retained request. Runtime revalidates it. */
    public void set(String control, float value) {
        var next = proposed(control, value);
        requested.clear(); requested.putAll(next.values());
    }

    /** A non-mutating batch for current-generation validation before a client command is accepted. */
    public MorphFrameOverrides proposed(String control, float value) {
        if (!CONTROLS.contains(control)) throw new IllegalArgumentException("Unknown morph control: " + control);
        float minimum = switch (control) { case "smile" -> -1F; case "breath" -> -.5F; default -> 0F; };
        if (!Float.isFinite(value) || value < minimum || value > 1)
            throw new IllegalArgumentException(control + " must be finite and in [" + minimum + ", 1]");
        var next = new LinkedHashMap<>(requested);
        next.put(BlendResourceId.parse("cpu_morph:" + control), value);
        return new MorphFrameOverrides(next);
    }

    public void selectClip(String clip) {
        if (!CLIPS.contains(clip)) throw new IllegalArgumentException("Unknown morph clip: " + clip);
        animation = BlendAnimationKey.parse("cpu_morph:" + clip);
    }

    public BlendAnimationKey animation() { return animation; }
    /** Fresh immutable batch each frame; omission resumes the animation/default, rather than zero. */
    public MorphFrameOverrides capture() { return new MorphFrameOverrides(requested); }
    public void reset() { requested.clear(); }
    public void event(BlendResourceId event) { lastEvent = Objects.requireNonNull(event); if (visualEvents < Long.MAX_VALUE) visualEvents++; }
    public long visualEvents() { return visualEvents; }
    public BlendResourceId lastEvent() { return lastEvent; }

    /** Entity unload/disconnect retires all mutable presentation state. */
    public void clear() {
        requested.clear(); animation = NOD; generation = -1; session = null; visualEvents = 0; lastEvent = null;
    }

    public String status() {
        return "clip=" + animation.value() + " requested=" + capture().values()
                + " events=" + visualEvents + " last=" + (lastEvent == null ? "none" : lastEvent.value())
                + " (omitted controls use clip/default values)";
    }
}
