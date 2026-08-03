package com.liy.blendlib.showcase.perf.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.showcase.perf.P7ReferenceScenario;
import java.util.Map;
import org.junit.jupiter.api.Test;

class P7BenchmarkScenePlanTest {
    @Test
    void planPreservesEveryFrozenPlacementAndItsCanonicalModelKey() {
        P7ReferenceScenario scenario = P7ReferenceScenario.standard();
        P7BenchmarkScenePlan plan = P7BenchmarkScenePlan.standard();
        Map<P7ReferenceScenario.Kind, String> expectedModelKeys = scenario.assets().stream()
                .collect(java.util.stream.Collectors.toMap(P7ReferenceScenario.Asset::kind,
                        P7ReferenceScenario.Asset::modelKey));

        assertEquals(P7ReferenceScenario.RIGID_INSTANCE_COUNT, plan.rigidCount());
        assertEquals(P7ReferenceScenario.SKINNED_INSTANCE_COUNT, plan.skinnedCount());
        assertEquals(scenario.instances().size(), plan.placements().size());
        assertEquals(scenario.camera(), plan.camera());
        assertEquals(plan.placements(), P7BenchmarkScenePlan.standard().placements());

        for (int index = 0; index < scenario.instances().size(); index++) {
            P7ReferenceScenario.Instance expected = scenario.instances().get(index);
            P7BenchmarkScenePlan.Placement actual = plan.placements().get(index);
            assertEquals(expected.kind(), actual.kind());
            assertEquals(expected.ordinal(), actual.ordinal());
            assertEquals(expected.x(), actual.x());
            assertEquals(expected.y(), actual.y());
            assertEquals(expected.z(), actual.z());
            assertEquals(expectedModelKeys.get(expected.kind()), actual.modelKey());
        }
        plan.validate();
    }

    @Test
    void planRetainsBothStrictP7ModelKeysWithoutInventingAThirdBinding() {
        assertEquals(
                java.util.Set.of("blendlib_showcase:p7/rigid_10k", "blendlib_showcase:p7/skinned_20k_64j"),
                P7BenchmarkScenePlan.standard().placements().stream()
                        .map(P7BenchmarkScenePlan.Placement::modelKey)
                        .collect(java.util.stream.Collectors.toSet()));
        assertFalse(P7BenchmarkScenePlan.standard().placements().isEmpty());
        assertTrue(P7BenchmarkScenePlan.standard().placements().stream()
                .allMatch(placement -> placement.modelKey().startsWith("blendlib_showcase:p7/")));
    }

    @Test
    void planProjectsTheAcceptedFourByTwentyFiveAndOneByTwentyFiveVisibilityLayout() {
        P7BenchmarkScenePlan plan = P7BenchmarkScenePlan.standard();

        assertEquals(new P7ReferenceScenario.CameraPose(0.0d, 67.0d, 24.0d, 180.0d, 0.0d), plan.camera());
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 25; column++) {
                int ordinal = 25 * row + column;
                P7BenchmarkScenePlan.Placement placement = plan.placements().get(ordinal);
                assertEquals(P7ReferenceScenario.Kind.RIGID, placement.kind());
                assertEquals(ordinal, placement.ordinal());
                assertEquals(-18.0d + 1.5d * column, placement.x());
                assertEquals(64.0d + 2.0d * row, placement.y());
                assertEquals(0.0d, placement.z());
            }
        }
        for (int column = 0; column < 25; column++) {
            P7BenchmarkScenePlan.Placement placement = plan.placements()
                    .get(P7ReferenceScenario.RIGID_INSTANCE_COUNT + column);
            assertEquals(P7ReferenceScenario.Kind.SKINNED, placement.kind());
            assertEquals(column, placement.ordinal());
            assertEquals(-18.0d + 1.5d * column, placement.x());
            assertEquals(72.0d, placement.y());
            assertEquals(0.0d, placement.z());
        }
    }
}
