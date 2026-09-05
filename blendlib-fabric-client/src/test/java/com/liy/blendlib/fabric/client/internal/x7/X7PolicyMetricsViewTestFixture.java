package com.liy.blendlib.fabric.client.internal.x7;

import java.util.List;

/** Test-only construction helper; production construction remains package-owned. */
public final class X7PolicyMetricsViewTestFixture {
    private X7PolicyMetricsViewTestFixture() {
    }

    public static X7PolicyMetricsView completed(long value, List<Long> lodSelections) {
        return X7PolicyMetricsView.completed(
                value,
                value,
                X7PolicyMetricsView.BackendRoute.GPU,
                X7PolicyMetricsView.FallbackReason.NONE,
                true,
                value,
                value,
                value,
                value,
                value,
                value,
                value,
                value,
                value,
                lodSelections,
                value,
                value,
                value,
                value,
                value,
                value,
                value,
                value,
                value,
                value,
                value,
                value,
                value,
                value,
                value,
                value,
                value,
                value,
                value,
                value,
                value,
                value,
                new X7PolicyMetricsView.GenerationUsage(value, value, value, value, value, value, value),
                new X7PolicyMetricsView.CompletedFrameWork(value, value, value, value, value, value));
    }
}
