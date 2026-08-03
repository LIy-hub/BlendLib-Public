package com.liy.blendlib.fabric.client.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.fabric.client.api.ClientAnimationRuntimeMetrics;
import com.liy.blendlib.fabric.client.api.ClientRenderMeasurementSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ClientRenderMeasurementCollectorTest {
    private static final BlendModelKey MODEL = BlendModelKey.parse("blendlib_test:benchmark/model");

    @AfterEach
    void resetCapture() {
        ClientRenderMeasurementCollector.resetForTests();
    }

    @Test
    void remainsDormantUntilAnExplicitCaptureBegins() {
        long animation = ClientRenderMeasurementCollector.startAnimationPreparation();
        ClientRenderMeasurementCollector.finishAnimationPreparation(animation);

        assertFalse(ClientRenderMeasurementCollector.completeFrame(ClientAnimationRuntimeMetrics::unavailable).isPresent());
    }

    @Test
    void drainsExactPerFrameCpuAndSubmittedModelObservations() {
        ClientRenderMeasurementCollector.beginCapture();
        long animation = ClientRenderMeasurementCollector.startAnimationPreparation();
        ClientRenderMeasurementCollector.finishAnimationPreparation(animation);
        long firstSubmit = ClientRenderMeasurementCollector.startSubmit();
        ClientRenderMeasurementCollector.finishSubmit(firstSubmit, MODEL);
        long secondSubmit = ClientRenderMeasurementCollector.startSubmit();
        ClientRenderMeasurementCollector.finishSubmit(secondSubmit, MODEL);

        ClientRenderMeasurementSnapshot first = ClientRenderMeasurementCollector.completeFrame(() -> metrics()).orElseThrow();
        assertTrue(first.animationPreparationNanos() >= 0L);
        assertTrue(first.submitCpuNanos() >= 0L);
        assertEquals(2, first.submittedModelCounts().get(MODEL));
        assertEquals(metrics(), first.animationRuntime());

        ClientRenderMeasurementSnapshot second = ClientRenderMeasurementCollector.completeFrame(() -> metrics()).orElseThrow();
        assertEquals(0L, second.animationPreparationNanos());
        assertEquals(0L, second.submitCpuNanos());
        assertTrue(second.submittedModelCounts().isEmpty());
    }

    private static ClientAnimationRuntimeMetrics metrics() {
        return new ClientAnimationRuntimeMetrics(true, 2, 8, 4L, 3L, 1L, 5, 1);
    }
}
