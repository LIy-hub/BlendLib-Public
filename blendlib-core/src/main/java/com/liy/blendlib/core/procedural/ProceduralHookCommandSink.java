package com.liy.blendlib.core.procedural;

/** Bounded hook sink. Implementations attach the hook's frozen identity and priority rather than trusting callers. */
public interface ProceduralHookCommandSink {
    void emit(ProceduralOperation operation);

    void emitVisualEvent(ProceduralVisualEvent event);
}
