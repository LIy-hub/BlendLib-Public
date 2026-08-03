package com.liy.blendlib.showcase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ShowcaseAnimatedActorEntityContractsTest {
    private static final Pattern BLENDLIB_IMPORT = Pattern.compile("(?m)^\\s*import\\s+(com\\.liy\\.blendlib\\.[\\w.]+);");
    private static final Pattern CLIENT_IMPORT = Pattern.compile("(?m)^\\s*import\\s+net\\.minecraft\\.client\\.");

    @Test
    void commonShowcaseRegistersASummonableAnimatedActorWithFixedGameplayDimensions() throws IOException {
        String entities = readSource("main", "com", "liy", "blendlib", "showcase", "entity", "ShowcaseEntities.java");
        String entrypoint = readSource("main", "com", "liy", "blendlib", "showcase", "BlendLibShowcaseEntrypoint.java");

        assertTrue(entities.contains("Identifier.fromNamespaceAndPath(\"blendlib_showcase\", \"animated_actor\")"));
        assertTrue(entities.contains("EntityType.Builder.of(ShowcaseAnimatedActorEntity::new, MobCategory.MISC)"));
        assertTrue(entities.contains("ANIMATED_ACTOR_GAMEPLAY_WIDTH = 0.60F"));
        assertTrue(entities.contains("ANIMATED_ACTOR_GAMEPLAY_HEIGHT = 1.80F"));
        assertTrue(entities.contains(".sized(ANIMATED_ACTOR_GAMEPLAY_WIDTH, ANIMATED_ACTOR_GAMEPLAY_HEIGHT)"));
        assertTrue(entities.contains(".build(ResourceKey.create(Registries.ENTITY_TYPE, ANIMATED_ACTOR_ENTITY_ID))"));
        assertTrue(entrypoint.contains("ShowcaseEntities.initialize()"));
    }

    @Test
    void animatedHostUsesOnlyThePublicServerFacadeWithoutVisualResourceOrPayloadDependency() throws IOException {
        String entity = readSource("main", "com", "liy", "blendlib", "showcase", "entity", "ShowcaseAnimatedActorEntity.java");
        String schedule = readSource("main", "com", "liy", "blendlib", "showcase", "entity",
                "ShowcaseAnimatedActorAttackSchedule.java");

        assertFalse(CLIENT_IMPORT.matcher(entity).find());
        assertFalse(CLIENT_IMPORT.matcher(schedule).find());
        assertEquals(Set.of("com.liy.blendlib.fabric.common.animation.BlendAnimations"),
                BLENDLIB_IMPORT.matcher(entity).results().map(match -> match.group(1)).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of("com.liy.blendlib.api.BlendAnimationKey"),
                BLENDLIB_IMPORT.matcher(schedule).results().map(match -> match.group(1)).collect(java.util.stream.Collectors.toSet()));
        for (String forbidden : List.of(
                "com.liy.blendlib.fabric.client",
                "BlendModelKey",
                "showcase_animation/showcase_actor",
                "ModelAsset",
                "ModelRenderSnapshot",
                "ResourceManager",
                "Payload",
                "FriendlyByteBuf",
                "EntityDataAccessor")) {
            assertFalse(entity.contains(forbidden), forbidden);
            assertFalse(schedule.contains(forbidden), forbidden);
        }
        assertTrue(entity.contains("return false;"));
        assertTrue(entity.contains("BlendAnimations.entity(this).trigger("));
        assertTrue(entity.contains("ShowcaseAnimatedActorAttackSchedule.ATTACK_ANIMATION_KEY"));
        assertFalse(entity.contains("setPersistent("));
    }

    @Test
    void mainSourceSetStillCannotReachClientAdapterOrSkinnedAssetPath() throws IOException {
        String mainSources = readJavaSources("main");

        for (String forbidden : List.of(
                "com.liy.blendlib.core.",
                "com.liy.blendlib.fabric.client.",
                "com.liy.blendlib.fabric.client",
                "showcase_animation/showcase_actor",
                "ModelRenderSnapshot")) {
            assertFalse(mainSources.contains(forbidden), forbidden);
        }
    }

    private static String readSource(String sourceSet, String... path) throws IOException {
        Path source = Path.of(System.getProperty("blendlib.projectDir"), "src", sourceSet, "java");
        for (String segment : path) {
            source = source.resolve(segment);
        }
        return Files.readString(source);
    }

    private static String readJavaSources(String sourceSet) throws IOException {
        Path root = Path.of(System.getProperty("blendlib.projectDir"), "src", sourceSet, "java");
        StringBuilder combined = new StringBuilder();
        try (var paths = Files.walk(root)) {
            for (Path source : paths.filter(path -> path.toString().endsWith(".java")).toList()) {
                combined.append(Files.readString(source));
            }
        }
        return combined.toString();
    }
}
