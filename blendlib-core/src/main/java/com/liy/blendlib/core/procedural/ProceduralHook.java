package com.liy.blendlib.core.procedural;

import com.liy.blendlib.api.BlendResourceId;

/** Explicit experimental hook. It receives only frozen frame data and can emit only controlled commands/events. */
public interface ProceduralHook {
    BlendResourceId id();

    int priority();

    void contribute(ProceduralHookContext context, ProceduralHookCommandSink sink);
}
