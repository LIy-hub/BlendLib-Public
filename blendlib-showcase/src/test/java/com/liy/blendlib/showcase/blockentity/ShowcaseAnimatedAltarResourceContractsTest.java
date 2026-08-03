package com.liy.blendlib.showcase.blockentity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.util.GsonHelper;
import org.junit.jupiter.api.Test;

class ShowcaseAnimatedAltarResourceContractsTest {
    @Test
    void baseBlockstateLinksExactlyToTheVanillaCubeAllBaseModel() throws IOException {
        Path assets = Path.of(
                System.getProperty("blendlib.projectDir"),
                "src", "main", "resources", "assets", "blendlib_showcase");
        JsonObject blockstate = parse(assets.resolve("blockstates/animated_altar.json"));
        JsonObject model = parse(assets.resolve("models/block/animated_altar.json"));

        JsonObject variants = blockstate.getAsJsonObject("variants");
        assertEquals(1, variants.size());
        assertTrue(variants.has(""));
        JsonObject defaultVariant = variants.getAsJsonObject("");
        assertEquals(1, defaultVariant.size());
        assertEquals("blendlib_showcase:block/animated_altar", defaultVariant.get("model").getAsString());

        assertEquals("minecraft:block/cube_all", model.get("parent").getAsString());
        JsonObject textures = model.getAsJsonObject("textures");
        assertEquals(1, textures.size());
        assertEquals("minecraft:block/stone", textures.get("all").getAsString());
        assertFalse(model.has("elements"));
        assertFalse(model.toString().contains("blend_models"));
        assertFalse(model.toString().contains("models3d"));
    }

    private static JsonObject parse(Path path) throws IOException {
        assertTrue(Files.isRegularFile(path), () -> "Missing resource " + path);
        return GsonHelper.parse(Files.readString(path));
    }
}
