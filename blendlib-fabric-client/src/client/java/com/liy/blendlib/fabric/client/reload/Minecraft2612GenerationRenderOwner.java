package com.liy.blendlib.fabric.client.reload;

import com.mojang.blaze3d.systems.RenderSystem;
import java.util.Objects;
import net.minecraft.client.Minecraft;

/** Trusted Minecraft 26.1.2 handoff and fenced-callback adapter for the D1 generation owner. */
final class Minecraft2612GenerationRenderOwner implements ClientGenerationResourceOwner.RenderOwnerCallbacks {
    @FunctionalInterface
    interface RenderThreadHandoff {
        void execute(Runnable task);
    }

    @FunctionalInterface
    interface RenderThreadAssertion {
        void assertOnRenderThread();
    }

    @FunctionalInterface
    interface FencedTaskQueue {
        void queue(Runnable task);
    }

    private final RenderThreadHandoff handoff;
    private final RenderThreadAssertion assertion;
    private final FencedTaskQueue fencedTasks;

    static Minecraft2612GenerationRenderOwner create() {
        return new Minecraft2612GenerationRenderOwner(
                Minecraft2612GenerationRenderOwner::executeOnMinecraft,
                RenderSystem::assertOnRenderThread,
                RenderSystem::queueFencedTask);
    }

    private static void executeOnMinecraft(Runnable task) {
        RenderThreadHandoff currentMinecraft = Minecraft.getInstance()::execute;
        currentMinecraft.execute(task);
    }

    Minecraft2612GenerationRenderOwner(
            RenderThreadHandoff handoff,
            RenderThreadAssertion assertion,
            FencedTaskQueue fencedTasks) {
        this.handoff = Objects.requireNonNull(handoff, "handoff");
        this.assertion = Objects.requireNonNull(assertion, "assertion");
        this.fencedTasks = Objects.requireNonNull(fencedTasks, "fencedTasks");
    }

    @Override
    public void handoffToRenderThread(Runnable task) {
        handoff.execute(Objects.requireNonNull(task, "task"));
    }

    @Override
    public void assertOnRenderThread() {
        assertion.assertOnRenderThread();
    }

    @Override
    public void queueFencedTask(Runnable callback) {
        fencedTasks.queue(Objects.requireNonNull(callback, "callback"));
    }
}
