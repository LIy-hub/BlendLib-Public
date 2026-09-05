package com.liy.blendlib.fabric.client.reload;

import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import com.liy.blendlib.fabric.client.render.X6DrawPrimitive;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Exact-generation, exact-handle hold released at most once. */
final class GenerationRenderResourceLease implements AutoCloseable {
    private final ClientGenerationResourceOwner owner;
    private final ModelRegistryGeneration generation;
    private final ModelHandle handle;
    private final boolean submittedChild;
    private final AtomicBoolean released = new AtomicBoolean();

    GenerationRenderResourceLease(
            ClientGenerationResourceOwner owner, ModelRegistryGeneration generation, ModelHandle handle) {
        this(owner, generation, handle, false);
    }

    GenerationRenderResourceLease(
            ClientGenerationResourceOwner owner, ModelRegistryGeneration generation, ModelHandle handle, boolean submittedChild) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.generation = Objects.requireNonNull(generation, "generation");
        this.handle = Objects.requireNonNull(handle, "handle");
        this.submittedChild = submittedChild;
    }

    long generationId() {
        return generation.generationId();
    }

    ModelHandle handle() {
        return handle;
    }

    boolean isReleased() {
        return released.get();
    }

    ClientGenerationResourceOwner.SubmittedLease beginSubmittedChild(
            ModelRenderSnapshot exactSnapshot, X6DrawPrimitive exactDraw) {
        if (submittedChild) {
            throw new IllegalStateException("A submitted child lease cannot mint another submitted child");
        }
        return owner.acquireSubmittedChild(this, exactSnapshot, exactDraw);
    }

    boolean belongsTo(ClientGenerationResourceOwner expectedOwner) {
        return owner == expectedOwner;
    }

    @Override
    public void close() {
        if (released.compareAndSet(false, true)) {
            owner.release(this);
        }
    }

    ModelRegistryGeneration generation() {
        return generation;
    }
}
