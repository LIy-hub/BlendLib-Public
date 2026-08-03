package com.liy.blendlib.showcase;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ShowcaseRigidEntityContractsTest {
    private static final Pattern BLENDLIB_IMPORT = Pattern.compile("(?m)^\\s*import\\s+com\\.liy\\.blendlib\\.");

    @Test
    void commonShowcaseRegistersASummonableRigidEntityWithoutModelDerivedGameplay() throws IOException {
        String entities = readSource("main", "com", "liy", "blendlib", "showcase", "entity", "ShowcaseEntities.java");
        String entity = readSource("main", "com", "liy", "blendlib", "showcase", "entity", "ShowcaseRigidEntity.java");

        assertTrue(entities.contains("Identifier.fromNamespaceAndPath(\"blendlib_showcase\", \"static_rigid\")"));
        assertTrue(entities.contains("EntityType.Builder.of(ShowcaseRigidEntity::new, MobCategory.MISC)"));
        assertTrue(entities.contains(".build(ResourceKey.create(Registries.ENTITY_TYPE, STATIC_RIGID_ENTITY_ID))"));
        assertFalse(BLENDLIB_IMPORT.matcher(entity).find());
        assertFalse(entity.contains("Model"));
    }

    @Test
    void clientShowcaseRegistersOnlyThePublicStaticRestPoseAdapter() throws IOException {
        String client = readSource("client", "com", "liy", "blendlib", "showcase", "client", "BlendLibShowcaseClientEntrypoint.java");
        assertTrue(client.contains("BlendEntityRenderers.register"));
        assertTrue(client.contains(".staticRestPose()"));
        assertFalse(client.contains("snapshotFactory("));
        assertFalse(client.contains("com.liy.blendlib.core."));
        assertFalse(client.contains("com.liy.blendlib.fabric.client.reload."));
        assertFalse(client.contains("ModelRenderSnapshot"));
    }

    @Test
    void staticFixtureDescriptorUsesRigidV1Profile() throws IOException {
        String descriptor = Files.readString(Path.of(
                System.getProperty("blendlib.projectDir"),
                "src", "main", "resources", "assets", "blendlib_showcase", "blend_models", "fixtures", "static_model.json"));
        assertTrue(descriptor.contains("\"profile\": \"blendlib:rigid_v1\""));
        assertFalse(descriptor.contains("skinned_v1"));
    }

    private static String readSource(String sourceSet, String... path) throws IOException {
        Path source = Path.of(System.getProperty("blendlib.projectDir"), "src", sourceSet, "java");
        for (String segment : path) {
            source = source.resolve(segment);
        }
        return Files.readString(source);
    }
}
