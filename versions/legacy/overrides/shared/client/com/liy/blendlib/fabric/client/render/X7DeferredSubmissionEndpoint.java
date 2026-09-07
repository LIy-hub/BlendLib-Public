package com.liy.blendlib.fabric.client.render;

import java.util.Objects;

/**
 * Explicit legacy capability boundary. The baseline X7 GPU admission was source-order disabled;
 * legacy Minecraft also lacks its native pipeline ABI. Every draw keeps the complete X6 CPU path.
 * This endpoint cannot mint, retain, submit or suppress a draw.
 */
public final class X7DeferredSubmissionEndpoint {
    private static final X7DeferredSubmissionEndpoint INSTANCE = new X7DeferredSubmissionEndpoint();
    private X7DeferredSubmissionEndpoint() { }
    public static X7DeferredSubmissionEndpoint bootstrap() {
        X6PlanSubmitter.installDeferredSubmissionEndpoint(INSTANCE);
        return INSTANCE;
    }
    public static void onClientReload() { }
    public boolean mayInstallHostEndpoint() { return false; }
    public void onReloadOrWorldLeave() { }
    static X7DeferredSubmissionEndpoint current() { return INSTANCE; }
    StageAResult tryPreAdmit(X6PreparedRenderPlan plan, ModelRenderSnapshot snapshot,
            X6DrawPrimitive draw, X6PlanSubmitter.FinalDrawInputs finalDrawInputs) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(draw, "draw");
        Objects.requireNonNull(finalDrawInputs, "finalDrawInputs");
        return StageAResult.KEEP_CPU_PIPELINE_UNAVAILABLE;
    }
    enum StageAResult {
        KEEP_CPU_SOURCE_ORDER_UNPROVEN, KEEP_CPU_PIPELINE_UNAVAILABLE, KEEP_CPU_ADMISSION_CLOSED,
        KEEP_CPU_PRE_ADMISSION_REJECTED, SUPPRESS_CPU_AFTER_FULL_ADMISSION
    }
}
