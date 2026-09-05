package com.liy.blendlib.fabric.client.procedural;

import com.liy.blendlib.core.procedural.ProceduralVisualEventBatch;
import java.util.Objects;

/** Deterministic presentation-only event dispatcher. Listener failures are isolated from snapshot publication. */
public final class ProceduralVisualEventDispatcher {
    public void dispatch(ProceduralVisualEventBatch batch, ProceduralVisualEventListener listener) {
        Objects.requireNonNull(batch, "batch");
        Objects.requireNonNull(listener, "listener");
        for (var event : batch.events()) {
            try {
                listener.present(event);
            } catch (RuntimeException | AssertionError ignored) {
                // Presentation callbacks cannot alter the already-frozen snapshot or create an authority result.
            }
        }
    }
}
