package com.liy.blendlib.showcase;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Contract guard for the opt-in P6 two-client harness; it is not a network integration test. */
class P6IsolatedSyncRunContractsTest {
    @Test
    void loomRunsKeepTheP6ServerAndBothOfflineTestClientsInDistinctDirectories() throws IOException {
        String build = Files.readString(projectRoot().resolve("build.gradle.kts"));

        assertTrue(build.contains("create(\"p6SyncServer\")"));
        assertTrue(build.contains("setConfigName(\"BlendLib Showcase P6 Sync Server (loopback isolated)\")"));
        assertTrue(build.contains("runDir(\"run/p6-sync-server\")"));
        assertTrue(build.contains("create(\"p6ClientA\")"));
        assertTrue(build.contains("runDir(\"run/p6-client-a\")"));
        assertTrue(build.contains("programArgs(\"--username\", \"BlendLibP6A\")"));
        assertTrue(build.contains("create(\"p6ClientB\")"));
        assertTrue(build.contains("runDir(\"run/p6-client-b\")"));
        assertTrue(build.contains("programArgs(\"--username\", \"BlendLibP6B\")"));
        assertTrue(build.contains("tasks.named(\"runP6SyncServer\")"));
        assertTrue(build.contains("dependsOn(prepareP6SyncServerRun)"));
        assertFalse(build.contains("runDir(\"run/p6-client\")"));
    }

    @Test
    void generatedServerTemplateAndManualChecklistRemainLocalOnlyAndRetainAllP6Observations() throws IOException {
        Path root = projectRoot().getParent();
        String template = Files.readString(root.resolve("test-assets/p6-sync-run/server.properties.template"));
        String manual = Files.readString(root.resolve("docs/manual-p6-sync-acceptance-v1.md"));

        assertTrue(template.contains("server-ip=127.0.0.1"));
        assertTrue(template.contains("server-port=25575"));
        assertTrue(template.contains("online-mode=false"));
        assertTrue(template.contains("level-name=blendlib-p6-sync-world"));
        assertTrue(template.contains("enable-rcon=false"));
        assertFalse(template.contains("server-port=25565"));
        assertFalse(template.contains("server-ip=0.0.0.0"));

        for (String required : new String[] {
                "runP6SyncServer", "runP6ClientA", "runP6ClientB", "127.0.0.1:25575",
                "tracking replay", "sequence replacement", "transient expiry", "disconnect",
                "dimension change", "late-packet risk", "D:\\MinecraftFabricServer-26.1.2",
                "D:\\MinecraftFabricServer-26.1.2-Fresh"}) {
            assertTrue(manual.contains(required), required);
        }
    }

    @Test
    void p6ServerPreparationAcceptsMinecraftGeneratedExtrasButProtectsEveryTemplateSafetyKey()
            throws IOException {
        String build = Files.readString(projectRoot().resolve("build.gradle.kts"));
        String serverPropertiesHelper = p6ServerPropertiesHelper(build);

        assertTrue(build.contains("fun createP6ServerPropertiesIfMissingOrVerifyRequiredSafety"));
        assertTrue(build.contains("val expectedP6Properties = loadProperties(expected)"));
        assertTrue(build.contains("expectedP6Properties.stringPropertyNames()"));
        assertTrue(build.contains("val actualP6Properties = loadProperties(destination.readText(StandardCharsets.UTF_8))"));
        assertTrue(build.contains("val actualValue = actualP6Properties.getProperty(key)"));
        assertTrue(build.contains("if (actualValue == expectedValue)"));
        assertTrue(build.contains("required safety setting(s) differ"));
        assertTrue(build.contains("the file was not overwritten"));
        assertTrue(build.contains("createOnlyIfMissingOrExact(p6SyncServerEula.asFile, \"eula=true\\n\")"));
        assertFalse(serverPropertiesHelper.contains("destination.readText(StandardCharsets.UTF_8) == expected"));
    }

    private static String p6ServerPropertiesHelper(String build) {
        String helperSignature = "fun createP6ServerPropertiesIfMissingOrVerifyRequiredSafety";
        int helperStart = build.indexOf(helperSignature);
        int helperEnd = build.indexOf(
                "createOnlyIfMissingOrExact(p6SyncServerEula.asFile", helperStart);
        assertTrue(helperStart >= 0, helperSignature);
        assertTrue(helperEnd > helperStart, "P6 server-properties helper end marker");
        return build.substring(helperStart, helperEnd);
    }

    private static Path projectRoot() {
        return Path.of(System.getProperty("blendlib.projectDir"));
    }
}
