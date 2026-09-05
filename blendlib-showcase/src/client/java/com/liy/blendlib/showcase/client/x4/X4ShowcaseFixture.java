package com.liy.blendlib.showcase.client.x4;

import com.liy.blendlib.fabric.client.render.RenderSubmissionContext;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * One explicit Showcase consumer for a typed X4 host target.
 *
 * <p>The fixture exposes the exact freeze/prepare/submit/drain lifecycle. It is deliberately not
 * wired into the normal Showcase client entrypoint yet: that shared bootstrap change belongs to
 * the integration handoff, while this consumer stays executable in isolation.</p>
 *
 * @param <F> client-only typed X4 extraction frame
 */
public final class X4ShowcaseFixture<F extends com.liy.blendlib.fabric.client.host.X4HostFrame> {
    private final String id;
    private final com.liy.blendlib.fabric.client.host.X4HostAdapter<F> adapter;
    private final F frame;
    private final BiConsumer<com.liy.blendlib.fabric.client.host.X4PreparedSnapshot, RenderSubmissionContext> submitter;

    X4ShowcaseFixture(
            String id,
            com.liy.blendlib.fabric.client.host.X4HostAdapter<F> adapter,
            F frame) {
        this.id = requireId(id);
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.frame = Objects.requireNonNull(frame, "frame");
        this.submitter = this.adapter::submit;
    }

    /** Human-readable fixture id, stable for Showcase assertions and visual-playtest handoff. */
    public String id() {
        return id;
    }

    /** Freezes configuration before any model-generation lookup can occur. */
    public void freeze() {
        adapter.freeze();
    }

    /** Prepares a generation-pinned immutable snapshot from the fixture frame. */
    public com.liy.blendlib.fabric.client.host.X4PreparedSnapshot prepare() {
        return adapter.prepare(frame);
    }

    /** Submits only the supplied prepared lease through the existing snapshot-only renderer seam. */
    public void submit(
            com.liy.blendlib.fabric.client.host.X4PreparedSnapshot prepared,
            RenderSubmissionContext context) {
        submitter.accept(prepared, context);
    }

    private static String requireId(String value) {
        value = Objects.requireNonNull(value, "id");
        if (value.isBlank()) {
            throw new IllegalArgumentException("X4 Showcase fixture id must not be blank");
        }
        return value;
    }
}
