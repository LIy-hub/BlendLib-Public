package com.liy.blendlib.fabric.client.render.x7gpu;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Stable-order, bounded static batch planner.
 *
 * <p>The planner combines only adjacent equal keys. This intentionally preserves input order
 * rather than sorting or moving instances across render-route/material/generation boundaries.
 * It emits a candidate description only: actual multi-index drawing and instancing remain
 * unavailable until a future render-pass and per-instance-transform contract exists.</p>
 */
final class X7StaticBatchPlanner {
    private X7StaticBatchPlanner() {
    }

    static X7StaticBatchPlan plan(List<X7StaticBatchInput> inputs, X7StaticBatchLimits limits) {
        List<X7StaticBatchInput> checkedInputs = List.copyOf(Objects.requireNonNull(inputs, "inputs"));
        X7StaticBatchLimits checkedLimits = Objects.requireNonNull(limits, "limits");
        if (checkedInputs.size() > checkedLimits.maxInstancesTotal()) {
            throw new IllegalArgumentException("X7 static batch input exceeds total instance bound");
        }

        List<X7StaticBatchPlan.Batch> batches = new ArrayList<>();
        Set<String> models = new LinkedHashSet<>();
        X7StaticBatchKey currentKey = null;
        List<String> currentInstances = null;
        for (X7StaticBatchInput input : checkedInputs) {
            X7StaticBatchInput checkedInput = Objects.requireNonNull(input, "input");
            String modelScope = checkedInput.key().generation() + "/" + checkedInput.key().modelId();
            models.add(modelScope);
            if (models.size() > checkedLimits.maxDistinctModels()) {
                throw new IllegalArgumentException("X7 static batch input exceeds distinct model bound");
            }
            if (currentKey == null
                    || !currentKey.equals(checkedInput.key())
                    || currentInstances.size() == checkedLimits.maxInstancesPerBatch()) {
                finishBatch(batches, currentKey, currentInstances, checkedLimits);
                currentKey = checkedInput.key();
                currentInstances = new ArrayList<>();
            }
            currentInstances.add(checkedInput.instanceId());
        }
        finishBatch(batches, currentKey, currentInstances, checkedLimits);
        return new X7StaticBatchPlan(
                batches,
                models.size(),
                checkedInputs.size(),
                X7StaticBatchPlan.SubmissionKind.DRAW_MULTIPLE_INDEXED_CANDIDATE_WAITING);
    }

    private static void finishBatch(
            List<X7StaticBatchPlan.Batch> batches,
            X7StaticBatchKey key,
            List<String> instances,
            X7StaticBatchLimits limits) {
        if (key == null) {
            return;
        }
        if (batches.size() == limits.maxBatches()) {
            throw new IllegalArgumentException("X7 static batch input exceeds batch bound");
        }
        batches.add(new X7StaticBatchPlan.Batch(key, instances));
    }
}
