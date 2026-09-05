package com.liy.blendlib.fabric.client.reload;

import com.liy.blendlib.fabric.client.api.ClientModelView;
import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import java.util.Objects;

/**
 * The one D1-record-owned live X7 policy state for a published generation.
 *
 * <p>This owner is intentionally not a global registry side map. The D1 lifecycle record creates
 * it before the transaction claim, and the record-state gate makes it observable only with the
 * successful generation CAS. The present candidate has no verified frame producer, so this owner
 * publishes a read-only unavailable metrics view and never records fictional frame actions.</p>
 */
final class X7ProductionPolicyOwner {
    private final X7PublishedGenerationProjection publishedProjection;
    private final X7PolicyMetricsCollector collector = new X7PolicyMetricsCollector();
    private final X7PolicyMetricsView unavailableMetrics;

    X7ProductionPolicyOwner(
            X7PublishedGenerationProjection publishedProjection,
            ClientGenerationResourceOwner.PolicyMetricsMaterializer policyMetricsMaterializer) {
        this.publishedProjection = Objects.requireNonNull(publishedProjection, "publishedProjection");
        for (int index = 0; index < publishedProjection.familyCount(); index++) {
            collector.recordBackendChoice(
                    X7GenerationPerformancePlan.BackendChoice.CPU,
                    X7GenerationPerformancePlan.FallbackReason.CAPABILITY_UNAVAILABLE);
        }
        // This immutable view, including its collector snapshot and map copies, must be complete before the
        // generation CAS. The D1 record-state gate still keeps it unobservable until that CAS succeeds.
        unavailableMetrics = Objects.requireNonNull(policyMetricsMaterializer, "policyMetricsMaterializer")
                .materialize(publishedProjection, collector);
    }

    X7PublishedGenerationProjection publishedProjection() {
        return publishedProjection;
    }

    X7PreparedFrameProjection prepareUnavailableFrame(
            ClientModelView sourceView, ModelRenderSnapshot snapshot) {
        return publishedProjection.prepareUnavailableFrame(sourceView, snapshot);
    }

    /** There is no completed render frame until a verified entrypoint owner is supplied. */
    X7PolicyMetricsView latestMetricsView() {
        return unavailableMetrics;
    }
}
