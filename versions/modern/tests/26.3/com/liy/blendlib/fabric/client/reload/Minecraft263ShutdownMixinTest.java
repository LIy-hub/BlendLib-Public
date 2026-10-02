package com.liy.blendlib.fabric.client.reload;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import net.fabricmc.loader.api.FabricLoader;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Validates transformed client hooks without creating a graphics device or joining a world. */
class Minecraft263ShutdownMixinTest {
    @Test void finalPresentHooksApplyToExactMinecraft263() throws Exception {
        assertTrue(FabricLoader.getInstance().isModLoaded("blendlib"));
        Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft", false, getClass().getClassLoader());
        for (String hook : new String[] {"blendlib$beforeOriginalFinalPresent", "blendlib$afterOriginalFinalPresent"}) {
            assertTrue(Arrays.stream(minecraft.getDeclaredMethods()).anyMatch(method -> method.getName().contains(hook)),
                    () -> "Missing transformed shutdown hook: " + hook);
        }
        try (var input = minecraft.getResourceAsStream("/net/minecraft/client/Minecraft.class")) {
            assertNotNull(input);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
            System.out.println("MINECRAFT_263_LOOM_CLASS_SHA256=" + hash);
            assertTrue(Minecraft2612OwnedFinalFenceAdapter.matchesPinnedMinecraftClassSha256(hash),
                    () -> "Unpinned 26.3 class: " + hash);
        }
    }
}
