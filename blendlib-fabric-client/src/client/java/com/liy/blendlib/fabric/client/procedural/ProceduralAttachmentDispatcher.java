package com.liy.blendlib.fabric.client.procedural;

import com.liy.blendlib.core.procedural.ProceduralFrameSnapshot;
import java.util.Objects;

/** Consumes only a frozen X3 snapshot and does not return acknowledgement or gameplay authority. */
public final class ProceduralAttachmentDispatcher {
    public void dispatch(ProceduralFrameSnapshot snapshot, ProceduralAttachmentPresentationResolver resolver) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(resolver, "resolver");
        snapshot.attachments().stream()
                .filter(value -> value.descriptor().visible())
                .forEach(value -> {
                    try {
                        resolver.present(new ProceduralAttachmentPresentation(value));
                    } catch (RuntimeException | AssertionError ignored) {
                        // Presentation callbacks cannot mutate the already-frozen frame or yield an authority result.
                    }
                });
    }
}
