package com.liy.blendlib.showcase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.showcase.client.ShowcaseAnimatedActorStateSchedule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Contract coverage for ADR-018's P5-only, no-synchronization actor fixture. */
class ShowcaseP5FallbackActorFixtureContractsTest {
    private static final Pattern BLENDLIB_IMPORT = Pattern.compile("(?m)^\\s*import\\s+com\\.liy\\.blendlib\\.");
    private static final Pattern CLIENT_IMPORT = Pattern.compile("(?m)^\\s*import\\s+net\\.minecraft\\.client\\.");

    @Test
    void fixtureHasItsOwnSummonableRegistrationAndExplicitGameplayShape() throws IOException {
        String entities = readSource("main", "com", "liy", "blendlib", "showcase", "entity", "ShowcaseEntities.java");
        String entrypoint = readSource("main", "com", "liy", "blendlib", "showcase", "BlendLibShowcaseEntrypoint.java");
        String registration = registrationBlock(entities);

        assertTrue(entities.contains("Identifier.fromNamespaceAndPath(\"blendlib_showcase\", \"p5_fallback_actor\")"));
        assertTrue(entities.contains("P5_FALLBACK_ACTOR_GAMEPLAY_WIDTH = 0.60F"));
        assertTrue(entities.contains("P5_FALLBACK_ACTOR_GAMEPLAY_HEIGHT = 1.80F"));
        assertTrue(registration.contains("EntityType.Builder.of(ShowcaseP5FallbackActorEntity::new, MobCategory.MISC)"));
        assertTrue(registration.contains(".sized(P5_FALLBACK_ACTOR_GAMEPLAY_WIDTH, P5_FALLBACK_ACTOR_GAMEPLAY_HEIGHT)"));
        assertTrue(registration.contains(".clientTrackingRange(8)"));
        assertTrue(registration.contains(".updateInterval(3)"));
        assertTrue(registration.contains(
                ".build(ResourceKey.create(Registries.ENTITY_TYPE, P5_FALLBACK_ACTOR_ENTITY_ID))"));
        assertTrue(entrypoint.contains("ShowcaseEntities.initialize()"));
    }

    @Test
    void serverFixtureContainsNoP6FacadeOrAnimationTrigger() throws IOException {
        String entity = readSource("main", "com", "liy", "blendlib", "showcase", "entity",
                "ShowcaseP5FallbackActorEntity.java");

        assertFalse(BLENDLIB_IMPORT.matcher(entity).find());
        assertFalse(CLIENT_IMPORT.matcher(entity).find());
        for (String forbidden : List.of(
                "BlendAnimations",
                "ShowcaseAnimatedActorAttackSchedule",
                "ShowcaseAnimatedActorEntity",
                "EntityDataAccessor",
                "Payload",
                "Packet",
                "ClientAnimation",
                "synchronizedSkinnedAnimation",
                "trigger(",
                "setPersistent(",
                "ShowcaseAnimatedActorStateSchedule")) {
            assertFalse(entity.contains(forbidden), forbidden);
        }
        assertTrue(entity.contains("return false;"));
        assertFalse(entity.contains("void tick()"));
    }

    @Test
    void clientBindingSelectsOnlyTheExistingLocalFallbackSchedule() throws IOException {
        String client = readSource("client", "com", "liy", "blendlib", "showcase", "client",
                "BlendLibShowcaseClientEntrypoint.java");
        String binding = clientBindingBlock(client);

        assertTrue(binding.contains("BlendEntityRenderer.<ShowcaseP5FallbackActorEntity>builder("));
        assertTrue(binding.contains("ShowcaseSkinnedAnimationBinding.MODEL_KEY"));
        assertTrue(binding.contains(
                ".skinnedAnimation((entity, request) -> ShowcaseAnimatedActorStateSchedule.stateAt(request.ageInTicks()))"));
        assertEquals(1, occurrences(binding, ".skinnedAnimation("));
        assertFalse(binding.contains("synchronizedSkinnedAnimation"));
        assertFalse(binding.contains("P6"));
        assertFalse(binding.contains("ClientAnimation"));
        assertFalse(binding.contains("Payload"));
        assertFalse(binding.contains("Packet"));
        assertFalse(binding.contains("network"));
        assertEquals(
                ShowcaseAnimatedActorStateSchedule.stateAt(0.0F),
                ShowcaseAnimatedActorStateSchedule.stateAt(ShowcaseAnimatedActorStateSchedule.CYCLE_TICKS));
    }

    private static String registrationBlock(String entities) {
        return sourceBlock(
                entities,
                "public static final EntityType<ShowcaseP5FallbackActorEntity> P5_FALLBACK_ACTOR",
                "P7_BENCHMARK_RIGID = Registry.register(");
    }

    private static String clientBindingBlock(String client) {
        return sourceBlock(
                client,
                "ShowcaseEntities.P5_FALLBACK_ACTOR",
                "ShowcaseEntities.P7_BENCHMARK_RIGID,");
    }

    private static String sourceBlock(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start);
        assertTrue(start >= 0, () -> "Missing source marker: " + startMarker);
        assertTrue(end > start, () -> "Missing source end marker: " + endMarker);
        return source.substring(start, end);
    }

    private static int occurrences(String source, String token) {
        int count = 0;
        int index = 0;
        while ((index = source.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }

    private static String readSource(String sourceSet, String... path) throws IOException {
        Path source = Path.of(System.getProperty("blendlib.projectDir"), "src", sourceSet, "java");
        for (String segment : path) {
            source = source.resolve(segment);
        }
        return Files.readString(source);
    }
}
