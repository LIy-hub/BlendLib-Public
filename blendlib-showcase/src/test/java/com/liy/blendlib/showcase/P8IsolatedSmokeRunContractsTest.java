package com.liy.blendlib.showcase;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Contract guard for future P8 current-manifest smoke runs; it does not launch Minecraft. */
class P8IsolatedSmokeRunContractsTest {
    @Test
    void loomRunsKeepTheP8ServerAndDevelopmentClientOutsideNormalAndP6Directories() throws IOException {
        String build = Files.readString(projectRoot().resolve("build.gradle.kts"));

        assertTrue(build.contains("create(\"p8CurrentManifestShowcaseServer\")"));
        assertTrue(build.contains("setConfigName(\"BlendLib Showcase P8 Current-Manifest Server (loopback isolated)\")"));
        assertTrue(build.contains("runDir(\"run/p8-current-manifest-server\")"));
        assertTrue(build.contains("create(\"p8CurrentManifestShowcaseClient\")"));
        assertTrue(build.contains("setConfigName(\"BlendLib Showcase P8 Current-Manifest Client (isolated)\")"));
        assertTrue(build.contains("runDir(\"run/p8-current-manifest-client\")"));
        assertTrue(build.contains("programArgs(\"--username\", \"BlendLibP8Smoke\")"));
        assertTrue(build.contains("tasks.named(\"runP8CurrentManifestShowcaseServer\")"));
        assertTrue(build.contains("dependsOn(prepareP8CurrentManifestShowcaseServerRun)"));
        assertFalse(build.contains("runDir(\"run/p8-current-manifest\")"));
    }

    @Test
    void generatedServerTemplateAndFutureProcedureStayLocalAndDoNotMislabelTheDevelopmentClasspath()
            throws IOException {
        Path root = projectRoot().getParent();
        String template = Files.readString(root.resolve("test-assets/p8-smoke-run/showcase-server.properties.template"));
        String procedure = Files.readString(root.resolve("docs/evidence/P8-isolated-smoke-harness.md"));

        assertTrue(template.contains("server-ip=127.0.0.1"));
        assertTrue(template.contains("server-port=25585"));
        assertTrue(template.contains("online-mode=false"));
        assertTrue(template.contains("level-name=blendlib-p8-showcase-current-manifest-smoke-world"));
        assertTrue(template.contains("enable-rcon=false"));
        assertFalse(template.contains("server-port=25565"));
        assertFalse(template.contains("server-ip=0.0.0.0"));

        for (String required : new String[] {
                "runP8CurrentManifestShowcaseServer", "runP8CurrentManifestShowcaseClient",
                "p8-current-manifest-server", "p8-current-manifest-client", "127.0.0.1:25585",
                "SHA256SUMS", "development classpath", "not an external Showcase JAR installer",
                "D:\\MinecraftFabricServer-26.1.2", "D:\\MinecraftFabricServer-26.1.2-Fresh", "P3--P7"}) {
            assertTrue(procedure.contains(required), required);
        }
    }

    @Test
    void p8ServerPreparationProtectsEveryTemplateSafetyKeyWithoutOverwritingExistingFiles()
            throws IOException {
        String build = Files.readString(projectRoot().resolve("build.gradle.kts"));
        String serverPropertiesHelper = p8ServerPropertiesHelper(build);

        assertTrue(build.contains("fun createP8ServerPropertiesIfMissingOrVerifyRequiredSafety"));
        assertTrue(build.contains("val expectedP8Properties = loadProperties(expected)"));
        assertTrue(build.contains("expectedP8Properties.stringPropertyNames()"));
        assertTrue(build.contains("val actualP8Properties = loadProperties(destination.readText(StandardCharsets.UTF_8))"));
        assertTrue(build.contains("val actualValue = actualP8Properties.getProperty(key)"));
        assertTrue(build.contains("required safety setting(s) differ"));
        assertTrue(build.contains("the file was not overwritten"));
        assertTrue(build.contains("createOnlyIfMissingOrExact(p8CurrentManifestShowcaseServerEula.asFile, \"eula=true\\n\")"));
        assertFalse(serverPropertiesHelper.contains("destination.readText(StandardCharsets.UTF_8) == expected"));
    }

    private static String p8ServerPropertiesHelper(String build) {
        String helperSignature = "fun createP8ServerPropertiesIfMissingOrVerifyRequiredSafety";
        int helperStart = build.indexOf(helperSignature);
        int helperEnd = build.indexOf(
                "createOnlyIfMissingOrExact(p8CurrentManifestShowcaseServerEula.asFile", helperStart);
        assertTrue(helperStart >= 0, helperSignature);
        assertTrue(helperEnd > helperStart, "P8 server-properties helper end marker");
        return build.substring(helperStart, helperEnd);
    }

    private static Path projectRoot() {
        return Path.of(System.getProperty("blendlib.projectDir"));
    }
}
