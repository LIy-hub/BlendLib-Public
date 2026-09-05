package com.liy.blendlib.fixture.localmaven;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Contract guard for the opt-in P8 Local Maven server smoke; it never launches a server. */
class P8CurrentManifestConsumerSmokeContractsTest {
    @Test
    void p8ConsumerServerUsesItsOwnLoopbackRunDirectory() throws IOException {
        String build = Files.readString(projectRoot().resolve("build.gradle.kts"));

        assertTrue(build.contains("create(\"p8CurrentManifestConsumerServer\")"));
        assertTrue(build.contains("setConfigName(\"BlendLib Local Maven Consumer P8 Current-Manifest Server (loopback isolated)\")"));
        assertTrue(build.contains("runDir(\"run/p8-current-manifest-server\")"));
        assertTrue(build.contains("tasks.named(\"runP8CurrentManifestConsumerServer\")"));
        assertTrue(build.contains("dependsOn(prepareP8CurrentManifestConsumerServerRun, verifyLocalMavenConsumerBoundary)"));
        assertTrue(build.contains("verifyLocalMavenConsumerBoundary"));
    }

    @Test
    void templateAndProcedureKeepTheConsumerSmokeLocalOnlyAndBoundToTheCurrentManifest()
            throws IOException {
        Path root = projectRoot().getParent();
        String template = Files.readString(root.resolve("test-assets/p8-smoke-run/local-maven-consumer-server.properties.template"));
        String procedure = Files.readString(root.resolve("docs/evidence/P8-isolated-smoke-harness.md"));

        assertTrue(template.contains("server-ip=127.0.0.1"));
        assertTrue(template.contains("server-port=25586"));
        assertTrue(template.contains("online-mode=false"));
        assertTrue(template.contains("level-name=blendlib-p8-local-maven-current-manifest-smoke-world"));
        assertTrue(template.contains("enable-rcon=false"));
        assertFalse(template.contains("server-port=25565"));
        assertFalse(template.contains("server-ip=0.0.0.0"));

        for (String required : new String[] {
                "runP8CurrentManifestConsumerServer", "p8-current-manifest-server", "127.0.0.1:25586",
                "com.liy.blendlib:blendlib-fabric", "SHA256SUMS", "P3--P7",
                "D:\\MinecraftFabricServer-26.1.2", "D:\\MinecraftFabricServer-26.1.2-Fresh"}) {
            assertTrue(procedure.contains(required), required);
        }
    }

    @Test
    void localMavenResolutionBoundaryRemainsExecutableBeforeTheP8ServerRun() throws IOException {
        String build = Files.readString(projectRoot().resolve("build.gradle.kts"));
        String serverPropertiesHelper = p8ServerPropertiesHelper(build);

        assertTrue(build.contains("implementation(blendLibCoordinate)"));
        assertTrue(build.contains("val verifyLocalMavenResolution = configurations.create(\"verifyLocalMavenResolution\")"));
        assertTrue(build.contains("resolvedPath.startsWith(expectedRepositoryRoot)"));
        assertTrue(build.contains("tasks.named(\"check\")"));
        assertTrue(build.contains("dependsOn(verifyLocalMavenConsumerBoundary)"));
        assertFalse(build.contains("implementation(project("));

        assertTrue(build.contains("fun createP8ServerPropertiesIfMissingOrVerifyRequiredSafety"));
        assertTrue(build.contains("expectedP8Properties.stringPropertyNames()"));
        assertTrue(build.contains("required safety setting(s) differ"));
        assertTrue(build.contains("the file was not overwritten"));
        assertTrue(build.contains("createOnlyIfMissingOrExact(p8CurrentManifestConsumerServerEula.asFile, \"eula=true\\n\")"));
        assertFalse(serverPropertiesHelper.contains("destination.readText(StandardCharsets.UTF_8) == expected"));
    }

    private static String p8ServerPropertiesHelper(String build) {
        String helperSignature = "fun createP8ServerPropertiesIfMissingOrVerifyRequiredSafety";
        int helperStart = build.indexOf(helperSignature);
        int helperEnd = build.indexOf(
                "createOnlyIfMissingOrExact(p8CurrentManifestConsumerServerEula.asFile", helperStart);
        assertTrue(helperStart >= 0, helperSignature);
        assertTrue(helperEnd > helperStart, "P8 consumer server-properties helper end marker");
        return build.substring(helperStart, helperEnd);
    }

    private static Path projectRoot() {
        return Path.of(System.getProperty("blendlib.projectDir"));
    }
}
