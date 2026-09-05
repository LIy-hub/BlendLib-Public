package com.liy.blendlib.fabric.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.Objects;
import net.minecraft.client.Minecraft;

/**
 * One synchronous permission window for the current Minecraft main render target.
 *
 * <p>This type deliberately owns no target, texture view, encoder, pass, or fence. Views exist only as local values
 * while {@link #withCurrentMainTarget(X7Minecraft2612PassOwnerHost.Phase, TargetWork)} invokes its work. A failure or
 * absent target/view fails closed and dispatches no work.</p>
 */
final class X7Minecraft2612TargetScope {
    @FunctionalInterface
    interface TargetWork {
        void run(
                X7Minecraft2612PassOwnerHost.Phase phase,
                GpuTextureView colorTarget,
                GpuTextureView depthTarget);
    }

    @FunctionalInterface
    interface RenderThreadAssertion {
        void assertOnRenderThread();
    }

    @FunctionalInterface
    interface MainTargetResolver {
        TargetViews resolve();
    }

    record TargetViews(GpuTextureView colorTarget, GpuTextureView depthTarget) {
    }

    private final RenderThreadAssertion renderThreadAssertion;
    private final MainTargetResolver mainTargetResolver;

    static X7Minecraft2612TargetScope production() {
        return new X7Minecraft2612TargetScope(RenderSystem::assertOnRenderThread, () -> {
            RenderTarget target = Minecraft.getInstance().getMainRenderTarget();
            if (target == null) {
                return null;
            }
            return new TargetViews(target.getColorTextureView(), target.getDepthTextureView());
        });
    }

    X7Minecraft2612TargetScope(RenderThreadAssertion renderThreadAssertion, MainTargetResolver mainTargetResolver) {
        this.renderThreadAssertion = Objects.requireNonNull(renderThreadAssertion, "renderThreadAssertion");
        this.mainTargetResolver = Objects.requireNonNull(mainTargetResolver, "mainTargetResolver");
    }

    TargetOutcome withCurrentMainTarget(X7Minecraft2612PassOwnerHost.Phase phase, TargetWork work) {
        if (phase == null || work == null) {
            return TargetOutcome.TARGET_WORK_FAILED;
        }
        try {
            renderThreadAssertion.assertOnRenderThread();
            TargetViews views = mainTargetResolver.resolve();
            if (views == null || views.colorTarget() == null || views.depthTarget() == null) {
                return TargetOutcome.TARGET_ABSENT;
            }
            work.run(phase, views.colorTarget(), views.depthTarget());
            return TargetOutcome.DISPATCHED;
        } catch (Error error) {
            throw error;
        } catch (Throwable ignored) {
            // The phase callback has no diagnostic object that may safely retain a target/view-bearing failure.
            return TargetOutcome.TARGET_WORK_FAILED;
        }
    }

    /** Package-private phase result; target absence and target-work failure must not collapse to one boolean. */
    enum TargetOutcome {
        DISPATCHED,
        TARGET_ABSENT,
        TARGET_WORK_FAILED
    }
}
