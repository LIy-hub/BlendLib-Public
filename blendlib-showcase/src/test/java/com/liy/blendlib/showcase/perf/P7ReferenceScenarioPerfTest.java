package com.liy.blendlib.showcase.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class P7ReferenceScenarioPerfTest {
    @Test
    void frozenScenarioKeepsTheRequiredCountsAndDeterministicPlacement() {
        P7ReferenceScenario scenario = P7ReferenceScenario.standard();

        assertEquals(100, P7ReferenceScenario.RIGID_INSTANCE_COUNT);
        assertEquals(10_000, P7ReferenceScenario.RIGID_TRIANGLES_PER_INSTANCE);
        assertEquals(25, P7ReferenceScenario.SKINNED_INSTANCE_COUNT);
        assertEquals(20_000, P7ReferenceScenario.SKINNED_TRIANGLES_PER_INSTANCE);
        assertEquals(64, P7ReferenceScenario.SKINNED_JOINTS_PER_INSTANCE);
        assertEquals(1_500_000, scenario.totalTriangleCount());
        assertEquals(125, scenario.instances().size());
        assertEquals(100, scenario.instances().stream().filter(instance -> instance.kind() == P7ReferenceScenario.Kind.RIGID).count());
        assertEquals(25, scenario.instances().stream().filter(instance -> instance.kind() == P7ReferenceScenario.Kind.SKINNED).count());
        assertEquals(scenario.instances(), P7ReferenceScenario.standard().instances());
        scenario.validate();
    }

    @Test
    void acceptedTrueInFrustumLayoutKeepsEveryOrdinalAtItsFrozenCoordinate() {
        P7ReferenceScenario scenario = P7ReferenceScenario.standard();

        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 25; column++) {
                int ordinal = 25 * row + column;
                P7ReferenceScenario.Instance instance = scenario.instances().get(ordinal);
                assertEquals(P7ReferenceScenario.Kind.RIGID, instance.kind());
                assertEquals(ordinal, instance.ordinal());
                assertEquals(-18.0d + 1.5d * column, instance.x());
                assertEquals(64.0d + 2.0d * row, instance.y());
                assertEquals(0.0d, instance.z());
            }
        }
        for (int column = 0; column < 25; column++) {
            P7ReferenceScenario.Instance instance = scenario.instances()
                    .get(P7ReferenceScenario.RIGID_INSTANCE_COUNT + column);
            assertEquals(P7ReferenceScenario.Kind.SKINNED, instance.kind());
            assertEquals(column, instance.ordinal());
            assertEquals(-18.0d + 1.5d * column, instance.x());
            assertEquals(72.0d, instance.y());
            assertEquals(0.0d, instance.z());
        }
    }

    @Test
    void aWarmupOrMeasurementFrameMustSubmitEveryFrozenTargetHost() {
        assertTrue(P7ReferenceScenario.hasExactTargetSubmissions(100, 25));
        assertTrue(!P7ReferenceScenario.hasExactTargetSubmissions(99, 25));
        assertTrue(!P7ReferenceScenario.hasExactTargetSubmissions(100, 24));
        assertTrue(!P7ReferenceScenario.hasExactTargetSubmissions(101, 25));
    }

    @Test
    void captureCameraUsesTheVanillaTeleportCentreOffsetAndAConservativeAngleTolerance() {
        P7ReferenceScenario scenario = P7ReferenceScenario.standard();

        assertEquals(new P7ReferenceScenario.CameraPose(0.0d, 67.0d, 24.0d, 180.0d, 0.0d), scenario.camera());
        assertEquals("/tp @s 0 67 24 180 0", P7ReferenceScenario.CAPTURE_TELEPORT_COMMAND);
        assertTrue(scenario.isAtCaptureCamera(0.5d, 67.0d, 24.5d, 180.0d, 0.0d));
        assertFalse(scenario.isAtCaptureCamera(0.0d, 67.0d, 24.0d, 180.0d, 0.0d));
        assertTrue(scenario.isAtCaptureCamera(
                0.5d + P7ReferenceScenario.CAMERA_POSITION_TOLERANCE_BLOCKS,
                67.0d,
                24.5d,
                180.0d - P7ReferenceScenario.CAMERA_ANGLE_TOLERANCE_DEGREES,
                P7ReferenceScenario.CAMERA_ANGLE_TOLERANCE_DEGREES));
        assertFalse(scenario.isAtCaptureCamera(
                0.5d + P7ReferenceScenario.CAMERA_POSITION_TOLERANCE_BLOCKS + 0.001d,
                67.0d,
                24.5d,
                180.0d,
                0.0d));
        assertFalse(scenario.isAtCaptureCamera(
                0.5d,
                67.0d,
                24.5d,
                180.0d + P7ReferenceScenario.CAMERA_ANGLE_TOLERANCE_DEGREES + 0.1d,
                0.0d));
    }

    @Test
    void fixedClientCaptureConditionsRequirePhysicalFramebufferFovAndEffectiveRenderDistance() {
        P7ReferenceScenario.ClientConditions accepted = new P7ReferenceScenario.ClientConditions(
                1_920, 1_080, 1_920, 1_080, 90, 0.0d, 8, 8);
        assertTrue(accepted.meetsCaptureContract());
        assertTrue(accepted.dynamicFovDisabled());
        assertTrue(accepted.hasRequiredAspectRatio());

        assertFalse(new P7ReferenceScenario.ClientConditions(
                1_920, 1_080, 1_600, 900, 90, 0.0d, 8, 8).meetsCaptureContract());
        assertFalse(new P7ReferenceScenario.ClientConditions(
                1_920, 1_080, 1_920, 1_080, 89, 0.0d, 8, 8).meetsCaptureContract());
        assertFalse(new P7ReferenceScenario.ClientConditions(
                1_920, 1_080, 1_920, 1_080, 90, 0.01d, 8, 8).meetsCaptureContract());
        assertFalse(new P7ReferenceScenario.ClientConditions(
                1_920, 1_080, 1_920, 1_080, 90, 0.0d, 8, 7).meetsCaptureContract());
        assertTrue(new P7ReferenceScenario.ClientConditions(
                1_600, 900, 1_600, 900, 90, 0.0d, 8, 8).hasRequiredAspectRatio());
        assertFalse(new P7ReferenceScenario.ClientConditions(
                1_600, 900, 1_600, 900, 90, 0.0d, 8, 8).meetsCaptureContract());
    }

    @Test
    void checkedInManifestsExactlyMatchTheCanonicalGenerator() throws Exception {
        String expected = P7ReferenceScenario.standard().canonicalManifestJson();
        Path showcase = Path.of(System.getProperty("blendlib.projectDir"));
        Path resourceManifest = showcase.resolve("src/main/resources/assets/blendlib_showcase/p7/reference-scene.json");
        Path fixtureManifest = showcase.getParent().resolve("test-assets/p7-performance/reference-scene.json");

        assertTrue(Files.isRegularFile(resourceManifest));
        assertTrue(Files.isRegularFile(fixtureManifest));
        assertEquals(expected, Files.readString(resourceManifest));
        assertEquals(expected, Files.readString(fixtureManifest));
        assertTrue(expected.contains("\"teleport_command\": \"/tp @s 0 67 24 180 0\""));
        assertTrue(expected.contains("\"framebuffer_width\": 1920"));
        assertTrue(expected.contains("\"dynamic_fov_disabled\": true"));
        assertTrue(expected.contains("\"ordinal\": \"25*r+c\""));
    }
}
