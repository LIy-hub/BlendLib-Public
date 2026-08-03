package com.liy.blendlib.showcase.blockentity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ShowcaseAnimatedAltarContractsTest {
    @Test
    void serverHostPublishesOnlyThePublicPersistentSemanticLoop() throws IOException {
        String blockEntity = readMain("blockentity", "ShowcaseAnimatedAltarBlockEntity.java");
        String animationKeys = readMain("blockentity", "ShowcaseBlockEntityAnimations.java");
        String blockEntities = readMain("blockentity", "ShowcaseBlockEntities.java");
        String blocks = readMain("block", "ShowcaseBlocks.java");
        String block = readMain("block", "ShowcaseAnimatedAltarBlock.java");

        assertTrue(blockEntity.contains(
                "BlendAnimations.blockEntity(blockEntity).setPersistent(ShowcaseBlockEntityAnimations.IDLE_LOOP)"));
        assertTrue(blockEntity.contains("persistentLoopPublished"));
        assertTrue(blockEntity.contains("instanceof ServerLevel"));
        assertTrue(animationKeys.contains("BlendAnimationKey.parse(\"blendlib_showcase:idle\")"));
        assertTrue(blockEntities.contains("FabricBlockEntityTypeBuilder.create"));
        assertTrue(blocks.contains("new ShowcaseAnimatedAltarBlock"));
        assertTrue(block.contains("ShowcaseAnimatedAltarBlockEntity::serverTick"));

        for (String forbidden : new String[] {
                "BlendModelKey", "BlendResourceId", "showcase_animation/showcase_actor", "net.minecraft.client.",
                "com.liy.blendlib.fabric.client.", "getServer(", "Packet", "Payload", "GLB", "matrix"}) {
            assertFalse(blockEntity.contains(forbidden), forbidden);
        }
    }

    @Test
    void clientBindingReusesTheStrictSkinnedAssetWithoutOpeningAnImplementationBackdoor() throws IOException {
        String binding = readClient("blockentity", "ShowcaseAnimatedAltarClientBinding.java");
        String entrypoint = readClient("BlendLibShowcaseClientEntrypoint.java");

        assertTrue(binding.contains("BlendModelKey.parse(\"blendlib_showcase:showcase_animation/showcase_actor\")"));
        assertTrue(entrypoint.contains("ShowcaseBlockEntities.ANIMATED_ALTAR"));
        assertTrue(entrypoint.contains("BlendBlockEntityRenderer.<ShowcaseAnimatedAltarBlockEntity>builder("));
        assertTrue(entrypoint.contains(".syncedSkinnedAnimation(ShowcaseBlockEntityAnimations.IDLE_LOOP)"));
        for (String forbidden : new String[] {
                "com.liy.blendlib.core.", "com.liy.blendlib.fabric.client.reload.",
                "com.liy.blendlib.fabric.client.animation.sync.", "com.liy.blendlib.fabric.common.",
                "ModelAssetLoader", "Files.", "Minecraft.getInstance", "org.lwjgl.opengl", "payload", "network"}) {
            assertFalse(binding.contains(forbidden), forbidden);
            assertFalse(entrypoint.contains(forbidden), forbidden);
        }
    }

    private static String readMain(String packageSegment, String filename) throws IOException {
        return Files.readString(Path.of(
                System.getProperty("blendlib.projectDir"),
                "src", "main", "java", "com", "liy", "blendlib", "showcase", packageSegment, filename));
    }

    private static String readClient(String... segments) throws IOException {
        Path path = Path.of(System.getProperty("blendlib.projectDir"), "src", "client", "java", "com", "liy", "blendlib", "showcase", "client");
        for (String segment : segments) {
            path = path.resolve(segment);
        }
        return Files.readString(path);
    }
}
