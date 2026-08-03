package com.liy.blendlib.showcase;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Source-boundary regression for the P5 rigid-palette Showcase host. */
class ShowcaseRigidPulseEntityContractsTest {
    @Test
    void commonHostStaysServerSafeAndClientBindingUsesOnlyTheExistingPublicBuilder() throws IOException {
        String entities = readSource("main", "com", "liy", "blendlib", "showcase", "entity", "ShowcaseEntities.java");
        String client = readSource("client", "com", "liy", "blendlib", "showcase", "client", "BlendLibShowcaseClientEntrypoint.java");

        assertTrue(entities.contains("Identifier.fromNamespaceAndPath(\"blendlib_showcase\", \"rigid_pulse\")"));
        assertTrue(entities.contains("EntityType<ShowcaseRigidEntity> RIGID_PULSE"));
        assertFalse(entities.contains("com.liy.blendlib.fabric.client"));
        assertTrue(client.contains("ShowcaseEntities.RIGID_PULSE"));
        assertTrue(client.contains("BlendModelKey.parse(\"blendlib_showcase:fixtures/rigid_model\")"));
        assertTrue(client.contains("BlendAnimationKey.parse(\"blendlib_showcase:rigid_pulse\")"));
        assertTrue(client.contains(".skinnedAnimation((entity, request) -> RIGID_PULSE)"));
        assertFalse(client.contains("snapshotFactory("));
        assertFalse(client.contains("com.liy.blendlib.core."));
        assertFalse(client.contains("com.liy.blendlib.fabric.client.reload."));
    }

    private static String readSource(String sourceSet, String... path) throws IOException {
        Path source = Path.of(System.getProperty("blendlib.projectDir"), "src", sourceSet, "java");
        for (String segment : path) {
            source = source.resolve(segment);
        }
        return Files.readString(source);
    }
}
