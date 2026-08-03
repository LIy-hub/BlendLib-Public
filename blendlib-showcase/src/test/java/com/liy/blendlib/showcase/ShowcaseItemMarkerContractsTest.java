package com.liy.blendlib.showcase;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ShowcaseItemMarkerContractsTest {
    @Test
    void staticItemUsesAValidVanillaMarkerAndClientOnlyExplicitBinding() throws IOException {
        Path project = Path.of(System.getProperty("blendlib.projectDir"));
        String marker = Files.readString(project.resolve(
                "src/main/resources/assets/blendlib_showcase/items/static_rigid_item.json"));
        assertTrue(marker.contains("\"type\": \"minecraft:model\""));
        assertTrue(marker.contains("\"model\": \"minecraft:item/stick\""));
        assertFalse(marker.contains("blendlib:model"));

        String main = Files.readString(project.resolve(
                "src/main/java/com/liy/blendlib/showcase/item/ShowcaseItems.java"));
        assertFalse(main.contains("com.liy.blendlib.fabric.client"));

        String client = Files.readString(project.resolve(
                "src/client/java/com/liy/blendlib/showcase/client/BlendLibShowcaseClientEntrypoint.java"));
        assertTrue(client.contains("BlendLibItemModelBindings.register"));
        assertTrue(client.contains("ShowcaseItems.STATIC_RIGID_ITEM_ID"));
        assertTrue(client.contains("BlendLibShowcaseEntrypoint.STATIC_RIGID_MODEL"));
    }
}
