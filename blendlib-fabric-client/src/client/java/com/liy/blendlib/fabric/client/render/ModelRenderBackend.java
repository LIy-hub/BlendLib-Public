package com.liy.blendlib.fabric.client.render;

/** Adapter SPI that consumes only a prepared immutable snapshot during submit. */
@FunctionalInterface
public interface ModelRenderBackend {
    void submit(ModelRenderSnapshot snapshot, RenderSubmissionContext context);
}
