package com.liy.blendlib.showcase;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ShowcaseSourceBoundaryTest {
    private static final Pattern BLENDLIB_IMPORT = Pattern.compile("import\\s+(com\\.liy\\.blendlib\\.[\\w.]+);");
    private static final String PUBLIC_COMMON_ANIMATION_FACADE =
            "com.liy.blendlib.fabric.common.animation.BlendAnimations";

    @Test
    void mainSourcesUseOnlyThePureApiAndExactPublicServerFacade() throws IOException {
        String source = readJavaSources("main");
        for (String forbiddenToken : List.of(
                ".impl.",
                "/impl/",
                "\\\\impl\\\\",
                "com.liy.blendlib.core.")) {
            assertFalse(source.contains(forbiddenToken), forbiddenToken);
        }
        String sourceWithoutAllowedFacade = source.replace(PUBLIC_COMMON_ANIMATION_FACADE, "");
        assertFalse(sourceWithoutAllowedFacade.contains("com.liy.blendlib.fabric."));
        Matcher matcher = BLENDLIB_IMPORT.matcher(source);
        while (matcher.find()) {
            String importedType = matcher.group(1);
            assertTrue(
                    importedType.startsWith("com.liy.blendlib.showcase.")
                            || importedType.startsWith("com.liy.blendlib.api.")
                            || importedType.equals(PUBLIC_COMMON_ANIMATION_FACADE),
                    () -> "Showcase main import escapes the public server surface: " + importedType);
        }
        assertTrue(source.contains("BlendModelKey.parse(\"blendlib_showcase:fixtures/static_model\")"));
        assertTrue(source.contains(PUBLIC_COMMON_ANIMATION_FACADE));
    }

    @Test
    void clientSourcesUseOnlyThePublicVersionSpecificAdapterSurface() throws IOException {
        String source = readJavaSources("client");
        for (String forbiddenToken : List.of(
                ".impl.",
                "/impl/",
                "\\\\impl\\\\",
                "com.liy.blendlib.core.",
                "com.liy.blendlib.fabric.client.reload.",
                "com.liy.blendlib.fabric.client.animation.sync.",
                "com.liy.blendlib.fabric.client.network.",
                "com.liy.blendlib.fabric.common.")) {
            assertFalse(source.contains(forbiddenToken), forbiddenToken);
        }
        Matcher matcher = BLENDLIB_IMPORT.matcher(source);
        while (matcher.find()) {
            String importedType = matcher.group(1);
            assertTrue(
                    importedType.startsWith("com.liy.blendlib.showcase.")
                            || importedType.startsWith("com.liy.blendlib.api.")
                            || importedType.startsWith("com.liy.blendlib.fabric.client.api.")
                            || importedType.startsWith("com.liy.blendlib.fabric.client.blockentity.")
                            || importedType.startsWith("com.liy.blendlib.fabric.client.entity.")
                            || importedType.startsWith("com.liy.blendlib.fabric.client.item.")
                            || importedType.startsWith("com.liy.blendlib.fabric.client.render."),
                    () -> "Client Showcase import escapes the public adapter surface: " + importedType);
        }
        assertTrue(source.contains(".staticRestPose()"));
        assertTrue(source.contains(".synchronizedSkinnedAnimation("));
        assertTrue(source.contains("BlendEntityRenderers.register"));
    }

    @Test
    void animatedActorWiringStaysOnThePublicExtractionOnlyAdapterSurface() throws IOException {
        String entrypoint = readJavaSources("client");
        for (String forbiddenToken : List.of(
                "com.liy.blendlib.core.",
                "com.liy.blendlib.fabric.client.reload.",
                "ClientModelRegistry",
                "ClientAnimationLifecycleBridge",
                "BlendLibClientAnimationSync",
                "ClientAnimationSyncRuntime",
                "ClientAnimationSyncStore",
                "UnknownTargetQueue",
                "Minecraft.getInstance",
                "com.liy.blendlib.fabric.client.network.",
                "com.liy.blendlib.fabric.client.animation.sync.",
                "com.liy.blendlib.fabric.common.network.",
                "CustomPacketPayload",
                ".submit(")) {
            assertFalse(entrypoint.contains(forbiddenToken), forbiddenToken);
        }
        assertTrue(entrypoint.contains("ShowcaseEntities.ANIMATED_ACTOR"));
        assertTrue(entrypoint.contains("ShowcaseSkinnedAnimationBinding.MODEL_KEY"));
        assertTrue(entrypoint.contains("ShowcaseAnimatedActorStateSchedule.stateAt(request.ageInTicks())"));
        assertTrue(entrypoint.contains(".synchronizedSkinnedAnimation("));
        assertTrue(entrypoint.contains("TIP_SOCKET_KEY"));
        assertTrue(entrypoint.contains(".skinnedSocketMarker(TIP_SOCKET_KEY)"));
    }

    private static String readJavaSources(String sourceSet) throws IOException {
        Path sourceRoot = Path.of(System.getProperty("blendlib.projectDir"), "src", sourceSet, "java");
        assertTrue(Files.isDirectory(sourceRoot), () -> "Missing source root: " + sourceRoot);
        StringBuilder combined = new StringBuilder();
        try (var paths = Files.walk(sourceRoot)) {
            for (Path sourceFile : paths.filter(path -> path.toString().endsWith(".java")).toList()) {
                combined.append(Files.readString(sourceFile));
            }
        }
        return combined.toString();
    }
}
