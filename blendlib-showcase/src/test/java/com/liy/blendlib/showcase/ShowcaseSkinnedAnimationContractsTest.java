package com.liy.blendlib.showcase;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ShowcaseSkinnedAnimationContractsTest {
    @Test
    void clientOnlyCatalogBindsTheCanonicalSkinnedModelAndThreeSemanticStates() throws IOException {
        String binding = readJava(
                "client",
                "com",
                "liy",
                "blendlib",
                "showcase",
                "client",
                "ShowcaseSkinnedAnimationBinding.java");
        String entrypoint = readJava(
                "client",
                "com",
                "liy",
                "blendlib",
                "showcase",
                "client",
                "BlendLibShowcaseClientEntrypoint.java");

        assertTrue(binding.contains("BlendModelKey.parse(\"blendlib_showcase:showcase_animation/showcase_actor\")"));
        assertTrue(binding.contains("BlendAnimationKey.parse(\"blendlib_showcase:idle\")"));
        assertTrue(binding.contains("BlendAnimationKey.parse(\"blendlib_showcase:walk\")"));
        assertTrue(binding.contains("BlendAnimationKey.parse(\"blendlib_showcase:attack\")"));
        assertTrue(binding.contains("List.of(IDLE, WALK, ATTACK)"));
        assertTrue(entrypoint.contains("ShowcaseSkinnedAnimationBinding.validateCanonicalContract()"));
        assertTrue(entrypoint.contains("package com.liy.blendlib.showcase.client;"));
        assertTrue(entrypoint.contains("implements ClientModInitializer"));
        assertTrue(entrypoint.contains("ShowcaseEntities.ANIMATED_ACTOR"));
        assertTrue(entrypoint.contains("BlendEntityRenderer.<ShowcaseAnimatedActorEntity>builder("));
        assertTrue(entrypoint.contains("ShowcaseSkinnedAnimationBinding.MODEL_KEY"));
        assertTrue(entrypoint.contains(
                "ShowcaseAnimatedActorStateSchedule.stateAt(request.ageInTicks())"));
        assertTrue(entrypoint.contains(".synchronizedSkinnedAnimation("));
        assertTrue(entrypoint.contains("BlendResourceId.parse(\"blendlib_showcase:tip\")"));
        assertTrue(entrypoint.contains(".skinnedSocketMarker(TIP_SOCKET_KEY)"));
        assertTrue(entrypoint.contains(".onSkinnedVisualEvent("));
        assertTrue(entrypoint.contains("BlendResourceId.parse(\"blendlib_showcase:attack_whoosh\")"));
        assertTrue(entrypoint.matches(
                "(?s).*\\.onSkinnedVisualEvent\\(\\(entity, eventKey\\) -> \\{\\s*"
                        + "if \\(!RenderSystem\\.isOnRenderThread\\(\\)\\s*"
                        + "\\|\\| entity\\.isRemoved\\(\\)\\s*"
                        + "\\|\\| entity\\.isInvisible\\(\\)\\s*"
                        + "\\|\\| !ATTACK_WHOOSH\\.equals\\(eventKey\\)\\) \\{\\s*return;.*"));
        assertTrue(entrypoint.contains("ParticleTypes.SWEEP_ATTACK"));
        assertTrue(entrypoint.contains("entity.level().addParticle("));
        assertFalse(entrypoint.contains("recordRenderCall("));
        assertFalse(entrypoint.contains("addAlwaysVisibleParticle("));
        assertFalse(entrypoint.contains("CompletableFuture"));
        assertFalse(entrypoint.contains("Executor"));
        assertFalse(entrypoint.contains("new Thread("));
        assertFalse(entrypoint.contains("org.lwjgl.opengl"));
        assertFalse(entrypoint.contains("GlStateManager"));
        assertFalse(entrypoint.contains("Minecraft.getInstance"));
        assertFalse(entrypoint.contains("com.liy.blendlib.core."));
        assertFalse(entrypoint.contains("com.liy.blendlib.fabric.client.reload."));
        assertFalse(entrypoint.contains("ClientModelRegistry"));
        assertFalse(entrypoint.contains("ClientAnimationLifecycleBridge"));
        assertFalse(entrypoint.contains("BlendLibClientAnimationSync"));
        assertFalse(entrypoint.contains("ClientAnimationSyncRuntime"));
        assertFalse(entrypoint.contains("ClientAnimationSyncStore"));
        assertFalse(entrypoint.contains("UnknownTargetQueue"));
        assertFalse(entrypoint.contains("com.liy.blendlib.fabric.client.animation.sync."));
        assertFalse(entrypoint.contains("com.liy.blendlib.fabric.client.network."));
        assertFalse(entrypoint.contains("import net.minecraft.server."));
        assertFalse(entrypoint.contains("payload"));
        assertFalse(entrypoint.contains("Packet"));
        assertFalse(entrypoint.contains("network"));
        assertFalse(entrypoint.contains("getServer("));
        assertFalse(entrypoint.contains("Damage"));
        assertFalse(entrypoint.contains("collision"));
        assertFalse(entrypoint.contains("consume("));
        assertFalse(entrypoint.contains("drop"));
        assertFalse(entrypoint.contains("hit"));
        assertFalse(entrypoint.contains("hurt("));
        assertFalse(entrypoint.contains("discard("));
        assertFalse(entrypoint.contains("spawnAtLocation("));
        assertFalse(binding.contains("no skinned instance-renderer binding seam yet"));
        assertFalse(binding.contains("com.liy.blendlib.fabric.client.animation."));
        assertFalse(binding.contains("com.liy.blendlib.fabric.client.reload."));
        assertFalse(binding.contains("ModelAssetLoader"));
        assertFalse(binding.contains("Files."));
        assertFalse(binding.contains("\".blend\""));
    }

    @Test
    void canonicalDescriptorMatchesTheClientSemanticBindingWithoutLeakingItIntoCommonSources() throws IOException {
        Path project = Path.of(System.getProperty("blendlib.projectDir"));
        String descriptor = Files.readString(project.resolve(
                "src/main/resources/assets/blendlib_showcase/blend_models/showcase_animation/showcase_actor.json"));
        String mainSources = readJavaSources("main");

        assertTrue(descriptor.contains("\"profile\": \"blendlib:skinned_v1\""));
        assertTrue(descriptor.contains("\"mesh\": \"blendlib_showcase:models3d/showcase_animation/showcase_actor.glb\""));
        assertTrue(descriptor.contains("\"initial_state\": \"blendlib_showcase:idle\""));
        assertTrue(descriptor.contains("\"blendlib_showcase:idle\""));
        assertTrue(descriptor.contains("\"blendlib_showcase:walk\""));
        assertTrue(descriptor.contains("\"blendlib_showcase:attack\""));
        assertTrue(descriptor.contains("\"next\": \"blendlib_showcase:idle\""));
        assertTrue(descriptor.contains("\"event\": \"blendlib_showcase:attack_whoosh\""));
        assertFalse(mainSources.contains("ShowcaseSkinnedAnimationBinding"));
        assertFalse(mainSources.contains("showcase_animation/showcase_actor"));
        assertFalse(mainSources.contains("com.liy.blendlib.fabric.client"));
    }

    private static String readJava(String sourceSet, String... segments) throws IOException {
        Path source = Path.of(System.getProperty("blendlib.projectDir"), "src", sourceSet, "java");
        for (String segment : segments) {
            source = source.resolve(segment);
        }
        return Files.readString(source);
    }

    private static String readJavaSources(String sourceSet) throws IOException {
        Path sourceRoot = Path.of(System.getProperty("blendlib.projectDir"), "src", sourceSet, "java");
        StringBuilder combined = new StringBuilder();
        try (var paths = Files.walk(sourceRoot)) {
            for (Path sourceFile : paths.filter(path -> path.toString().endsWith(".java")).toList()) {
                combined.append(Files.readString(sourceFile));
            }
        }
        return combined.toString();
    }
}
