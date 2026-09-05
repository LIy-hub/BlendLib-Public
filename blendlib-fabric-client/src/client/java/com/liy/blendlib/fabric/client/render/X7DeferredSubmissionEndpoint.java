package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.fabric.client.X7T3cClientGateway;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.Objects;

/**
 * Narrow client-only bootstrap and phase endpoint for a future X7 deferred submission path.
 *
 * <p>It intentionally exposes no D1 lease, policy record, queue request, raw buffer, target
 * owner, encoder, pass, or live render context. The current production endpoint installs the real
 * client-private registration/admission path, but remains deliberately inactive because Fabric's
 * two visible callbacks do not prove that X6 Stage A can reserve the matching AFTER_SOLID epoch
 * before it seals. Thus the host retains its {@code NO_PREPARED_DRAW} scope until that proof exists.</p>
 */
public final class X7DeferredSubmissionEndpoint {
    private static volatile X7DeferredSubmissionEndpoint current = new X7DeferredSubmissionEndpoint(
            new X7AfterSolidQueueDriver(new X7AfterSolidQueueDriver.UnavailableQueueEpochPort()));

    private final X7AfterSolidQueueDriver queueDriver;

    private X7DeferredSubmissionEndpoint(X7AfterSolidQueueDriver queueDriver) {
        this.queueDriver = Objects.requireNonNull(queueDriver, "queueDriver");
    }

    /**
     * Creates and installs the real production gateway internally. No caller-supplied gateway can cross this
     * bootstrap boundary, so an arbitrary focused-test source-order supplier can never install a host endpoint.
     */
    public static X7DeferredSubmissionEndpoint bootstrap() {
        X7DeferredSubmissionEndpoint endpoint = new X7DeferredSubmissionEndpoint(
                new X7AfterSolidQueueDriver(new GatewayPort(X7T3cClientGateway.production())));
        current = endpoint;
        X6PlanSubmitter.installDeferredSubmissionEndpoint(endpoint);
        return endpoint;
    }

    /** Stops new admission before a model-reload application can retire generation-owned state. */
    public static void onClientReload() {
        current.onReloadOrWorldLeave();
    }

    /** True only after both source-order proof and exact T3b registration are present. */
    public boolean mayInstallHostEndpoint() {
        return queueDriver.mayInstallHostEndpoint();
    }

    /** Synchronous AFTER_SOLID callback; target views never escape this method. */
    public void onAfterSolid(GpuTextureView colorTarget, GpuTextureView depthTarget) {
        queueDriver.onAfterSolid(colorTarget, depthTarget);
    }

    /** Synchronous BEFORE_TRANSLUCENT callback; it closes only no-command epoch work. */
    public void onBeforeTranslucent(GpuTextureView colorTarget, GpuTextureView depthTarget) {
        queueDriver.onBeforeTranslucent(colorTarget, depthTarget);
    }

    /** World-leave/T1c cutoff. Submitted or uncertain command work remains port-owned. */
    public void onReloadOrWorldLeave() {
        queueDriver.onReloadOrWorldLeave();
    }

    StageAResult tryPreAdmit(
            X6PreparedRenderPlan plan,
            ModelRenderSnapshot snapshot,
            X6DrawPrimitive draw,
            X6PlanSubmitter.FinalDrawInputs finalDrawInputs) {
        return StageAResult.from(queueDriver.tryPreAdmit(plan, snapshot, draw, finalDrawInputs));
    }

    static X7DeferredSubmissionEndpoint current() {
        return current;
    }

    static X7DeferredSubmissionEndpoint forTest(X7AfterSolidQueueDriver.OptionalT4Adapter port) {
        return new X7DeferredSubmissionEndpoint(new X7AfterSolidQueueDriver(port));
    }

    enum StageAResult {
        KEEP_CPU_SOURCE_ORDER_UNPROVEN,
        KEEP_CPU_PIPELINE_UNAVAILABLE,
        KEEP_CPU_ADMISSION_CLOSED,
        KEEP_CPU_PRE_ADMISSION_REJECTED,
        SUPPRESS_CPU_AFTER_FULL_ADMISSION;

        private static StageAResult from(X7AfterSolidQueueDriver.StageAResult result) {
            return valueOf(Objects.requireNonNull(result, "result").name());
        }
    }

    /** Render-package adapter that guarantees a rejected Stage-A path closes its minted child before CPU resumes. */
    private static final class GatewayPort implements X7AfterSolidQueueDriver.OptionalT4Adapter {
        private final X7T3cClientGateway gateway;

        private GatewayPort(X7T3cClientGateway gateway) {
            this.gateway = Objects.requireNonNull(gateway, "gateway");
        }

        @Override
        public boolean provesStageABeforeAfterSolidSeal() {
            return gateway.provesStageABeforeAfterSolidSeal();
        }

        @Override
        public boolean hasRegisteredT3bPipeline() {
            return gateway.hasRegisteredPipelines();
        }

        @Override
        public X7AfterSolidQueueDriver.StageAResult tryPreAdmit(
                X6PreparedRenderPlan plan,
                ModelRenderSnapshot snapshot,
                X6DrawPrimitive draw,
                X6PlanSubmitter.FinalDrawInputs finalDrawInputs) {
            X7T3cFrozenStageA frozen = null;
            try {
                frozen = X7T3cFrozenStageA.freezeAndMint(plan, snapshot, draw, finalDrawInputs);
                if (!frozen.retainsExactPlan(plan)) {
                    frozen.close();
                    return X7AfterSolidQueueDriver.StageAResult.KEEP_CPU_PRE_ADMISSION_REJECTED;
                }
                if (gateway.tryAdmit(frozen)) {
                    return X7AfterSolidQueueDriver.StageAResult.SUPPRESS_CPU_AFTER_FULL_ADMISSION;
                }
                frozen.close();
                return X7AfterSolidQueueDriver.StageAResult.KEEP_CPU_PRE_ADMISSION_REJECTED;
            } catch (Error error) {
                closeAfterFailure(frozen, error);
                throw error;
            } catch (RuntimeException rejected) {
                if (frozen != null) {
                    try {
                        frozen.close();
                    } catch (Throwable cleanup) {
                        if (cleanup != rejected) {
                            rejected.addSuppressed(cleanup);
                        }
                    }
                }
                return X7AfterSolidQueueDriver.StageAResult.KEEP_CPU_PRE_ADMISSION_REJECTED;
            }
        }

        @Override
        public X7AfterSolidQueueDriver.StageBResult sealDrainAndExecute(
                GpuTextureView colorTarget, GpuTextureView depthTarget) {
            gateway.sealDrainAndExecute(colorTarget, depthTarget);
            return X7AfterSolidQueueDriver.StageBResult.RETAIN_SUBMITTED_OR_UNCERTAIN_NO_CPU_REPLAY;
        }

        @Override
        public void pollRetainedCompletionFences() {
            gateway.pollRetainedCompletionFences();
        }

        @Override
        public void cancelNoCommandForAfterSolidEpoch() {
            gateway.cancelNoCommandForAfterSolidEpoch();
        }

        @Override
        public void closeAdmissionAndCancelNoCommand() {
            gateway.closeAdmissionAndCancelNoCommand();
        }

        private static void closeAfterFailure(X7T3cFrozenStageA frozen, Error primary) {
            if (frozen == null) {
                return;
            }
            try {
                frozen.close();
            } catch (Throwable cleanup) {
                if (cleanup != primary) {
                    primary.addSuppressed(cleanup);
                }
            }
        }
    }
}
