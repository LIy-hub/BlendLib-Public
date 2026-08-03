package com.liy.blendlib.fixture.fabric;

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

class FabricConsumerFixtureBoundaryTest {
    private static final Pattern BLENDLIB_IMPORT = Pattern.compile("(?m)^\\s*import\\s+(com\\.liy\\.blendlib\\.[\\w.]+);");
    private static final Set<String> ALLOWED_BLENDLIB_IMPORTS = Set.of(
            "com.liy.blendlib.api.BlendAnimationKey",
            "com.liy.blendlib.api.BlendModelKey",
            "com.liy.blendlib.api.BlendResourceId",
            "com.liy.blendlib.fabric.common.animation.BlendAnimations");

    @Test
    void fixtureCompilesAgainstTheDocumentedSemanticKeys() {
        assertEquals("consumer:fixture_actor", FabricConsumerFixture.canonicalModelId().value());
        assertEquals("consumer:attack", FabricConsumerFixture.ATTACK_KEY.value());
    }

    @Test
    void fixtureSourceImportsOnlyPublicApiAndExactCommonFacade() throws IOException {
        String source = Files.readString(Path.of(
                System.getProperty("blendlib.projectDir"),
                "src", "main", "java", "com", "liy", "blendlib", "fixture", "fabric",
                "FabricConsumerFixture.java"));

        Set<String> imports = BLENDLIB_IMPORT.matcher(source).results()
                .map(match -> match.group(1))
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(ALLOWED_BLENDLIB_IMPORTS, imports);
        for (String forbidden : List.of(
                ".impl.",
                "com.liy.blendlib.core.",
                "com.liy.blendlib.fabric.common.network.",
                "com.liy.blendlib.fabric.client.",
                "loader.",
                "parser.",
                "resource.",
                "render.",
                "backend.")) {
            assertFalse(source.contains(forbidden), forbidden);
        }
        assertTrue(source.contains("BlendAnimations.entity"));
        assertTrue(source.contains(".trigger(ATTACK_KEY, speed, seed)"));
    }
}
