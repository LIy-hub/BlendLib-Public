package com.liy.blendlib.fabric.client.procedural;

import com.liy.blendlib.core.procedural.ResolvedProceduralVisualEvent;

/** Void presentation listener: it has no result channel that could influence gameplay authority. */
@FunctionalInterface
public interface ProceduralVisualEventListener {
    void present(ResolvedProceduralVisualEvent event);
}
