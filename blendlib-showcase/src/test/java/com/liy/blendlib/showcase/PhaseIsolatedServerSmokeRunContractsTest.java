package com.liy.blendlib.showcase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Contract guard for phase-specific dedicated-server smoke harnesses. These tests are deliberately
 * static and never launch Minecraft.
 */
class PhaseIsolatedServerSmokeRunContractsTest {
    private static final List<Harness> HARNESSES = List.of(
            new Harness("P3", "p3SmokeServer", "run/p3-smoke-server", "25571", "blendlib-p3-smoke-world"),
            new Harness("P4", "p4SmokeServer", "run/p4-smoke-server", "25572", "blendlib-p4-smoke-world"),
            new Harness("P5", "p5SmokeServer", "run/p5-smoke-server", "25573", "blendlib-p5-smoke-world"),
            new Harness("P7", "p7SmokeServer", "run/p7-smoke-server", "25574", "blendlib-p7-smoke-world"));

    @Test
    void loomRunsKeepEveryPhaseSmokeServerOutsideTheDefaultAndExistingHarnessDirectories() throws IOException {
        String build = Files.readString(projectRoot().resolve("build.gradle.kts"));

        assertTrue(build.contains("named(\"server\")"));
        assertTrue(build.contains("setConfigName(\"BlendLib Showcase Server\")"));
        assertTrue(build.contains("runDir(\"run/server\")"));
        assertTrue(build.contains("val phaseSmokeServerHarnesses = listOf("));
        assertTrue(build.contains("tasks.register(\"prepare${harness.phase}SmokeServerRun\")"));
        assertTrue(build.contains("tasks.named(\"run${harness.phase}SmokeServer\")"));
        for (Harness harness : HARNESSES) {
            assertTrue(build.contains("create(\"" + harness.runName() + "\")"), harness.phase());
            assertTrue(build.contains("setConfigName(\"BlendLib Showcase " + harness.phase()
                    + " Smoke Server (loopback isolated)\")"), harness.phase());
            assertTrue(build.contains("runDir(\"" + harness.runDirectory() + "\")"), harness.phase());
            assertTrue(build.contains("PhaseSmokeServerHarness(\"" + harness.phase() + "\", \""
                    + harness.runDirectory() + "\", \"test-assets/"
                    + harness.phase().toLowerCase() + "-smoke-run/server.properties.template\")"), harness.phase());
        }
        assertFalse(build.contains("runDir(\"run/p3-smoke\")"));
        assertFalse(build.contains("runDir(\"run/p4-smoke\")"));
        assertFalse(build.contains("runDir(\"run/p5-smoke\")"));
        assertFalse(build.contains("runDir(\"run/p7-smoke\")"));
    }

    @Test
    void templatesUseDistinctLoopbackOnlyPortsWorldsAndSafetyKeys() throws IOException {
        Path root = projectRoot().getParent();
        Set<String> ports = new TreeSet<>();
        Set<String> worlds = new TreeSet<>();

        for (Harness harness : HARNESSES) {
            String template = Files.readString(root.resolve("test-assets")
                    .resolve(harness.phase().toLowerCase() + "-smoke-run/server.properties.template"));
            Properties properties = new Properties();
            properties.load(new StringReader(template));

            assertEquals("127.0.0.1", properties.getProperty("server-ip"), harness.phase());
            assertEquals(harness.port(), properties.getProperty("server-port"), harness.phase());
            assertEquals("false", properties.getProperty("online-mode"), harness.phase());
            assertEquals("false", properties.getProperty("enforce-secure-profile"), harness.phase());
            assertEquals("false", properties.getProperty("enable-rcon"), harness.phase());
            assertEquals("false", properties.getProperty("white-list"), harness.phase());
            assertEquals("false", properties.getProperty("enforce-whitelist"), harness.phase());
            assertEquals(harness.world(), properties.getProperty("level-name"), harness.phase());
            assertTrue(ports.add(harness.port()), "duplicate port: " + harness.port());
            assertTrue(worlds.add(harness.world()), "duplicate world: " + harness.world());
            assertFalse(Set.of("25565", "25575", "25585", "25586").contains(harness.port()), harness.phase());
            assertFalse(template.contains("server-ip=0.0.0.0"), harness.phase());
        }
    }

    @Test
    void preparationChecksEveryTemplateSafetyKeyAndRefusesMismatchWithoutOverwriting() throws IOException {
        String build = Files.readString(projectRoot().resolve("build.gradle.kts"));
        String helper = phaseSmokeServerPropertiesHelper(build);

        assertTrue(build.contains("fun createPhaseSmokeFileOnlyIfMissingOrExact"));
        assertTrue(build.contains("fun createPhaseSmokeServerPropertiesIfMissingOrVerifyRequiredSafety"));
        assertTrue(helper.contains("val expectedProperties = loadPhaseSmokeServerProperties(expected)"));
        assertTrue(helper.contains("expectedProperties.stringPropertyNames()"));
        assertTrue(helper.contains("val actualProperties = loadPhaseSmokeServerProperties(destination.readText(StandardCharsets.UTF_8))"));
        assertTrue(helper.contains("val actualValue = actualProperties.getProperty(key)"));
        assertTrue(helper.contains("required safety setting(s) differ"));
        assertTrue(helper.contains("the file was not overwritten"));
        assertFalse(helper.contains("destination.readText(StandardCharsets.UTF_8) == expected"));
    }

    @Test
    void procedureCorrectsHistoricalDefaultRunEvidenceAndKeepsFuturePhaseSmokesLocalOnly() throws IOException {
        String procedure = Files.readString(projectRoot().getParent()
                .resolve("docs/evidence/isolated-server-harness-p3-p4-p5-p7.md"));

        assertTrue(procedure.contains("HISTORICAL NON-GATE DEFAULT-RUN RECORD"));
        assertTrue(procedure.contains("not phase Gate evidence"));
        assertTrue(procedure.contains("do not launch Minecraft"));
        assertTrue(procedure.contains("D:\\MinecraftFabricServer-26.1.2"));
        assertTrue(procedure.contains("D:\\MinecraftFabricServer-26.1.2-Fresh"));
        for (Harness harness : HARNESSES) {
            assertTrue(procedure.contains("run" + harness.phase() + "SmokeServer"), harness.phase());
            assertTrue(procedure.contains(harness.runDirectory()), harness.phase());
            assertTrue(procedure.contains("127.0.0.1:" + harness.port()), harness.phase());
            assertTrue(procedure.contains(harness.world()), harness.phase());
        }
    }

    private static String phaseSmokeServerPropertiesHelper(String build) {
        String helperSignature = "fun createPhaseSmokeServerPropertiesIfMissingOrVerifyRequiredSafety";
        int helperStart = build.indexOf(helperSignature);
        int helperEnd = build.indexOf("val phaseSmokeServerHarnesses", helperStart);
        assertTrue(helperStart >= 0, helperSignature);
        assertTrue(helperEnd > helperStart, "phase smoke server-properties helper end marker");
        return build.substring(helperStart, helperEnd);
    }

    private static Path projectRoot() {
        return Path.of(System.getProperty("blendlib.projectDir"));
    }

    private record Harness(String phase, String runName, String runDirectory, String port, String world) {
    }
}
