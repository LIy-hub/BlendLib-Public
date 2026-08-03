package com.liy.blendlib.fabric.client.reload;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.fabric.client.render.ModelRenderHandle;

/** Immutable client-generation handle selected by the reload registry. */
public sealed interface ModelHandle permits LoadedModelHandle, MissingModelHandle {
    BlendModelKey key();

    long generationId();

    /** Immutable backend-ready data; lookup never parses assets or constructs a static/rigid handle. */
    ModelRenderHandle renderHandle();

    boolean missing();
}
