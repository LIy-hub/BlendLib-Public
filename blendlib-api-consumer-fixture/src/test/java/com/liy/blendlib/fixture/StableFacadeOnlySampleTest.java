package com.liy.blendlib.fixture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.liy.blendlib.api.BlendLib;
import com.liy.blendlib.api.HostKind;
import com.liy.blendlib.api.PlaybackMode;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class StableFacadeOnlySampleTest {
    @Test
    void ordinarySampleCompilesAgainstOnlyStableFacadeAndKeys() {
        assertEquals(HostKind.ENTITY, StableFacadeOnlySample.entitySpecification().hostKind());
        assertEquals(ApiConsumerFixture.MODEL, StableFacadeOnlySample.entitySpecification().model());
        assertEquals(HostKind.ITEM, StableFacadeOnlySample.itemSpecification().hostKind());
        assertEquals(PlaybackMode.LOOP,
                StableFacadeOnlySample.itemSpecification().animationSource().requestFor("stable-only-item").playbackMode());
    }

    @Test
    void stableFacadePublicMethodsDoNotExposeExperimentalSpiTypes() {
        for (Method method : BlendLib.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            assertFalse(method.getReturnType().getPackageName().contains(".spi.experimental"), method::toString);
            for (Class<?> parameterType : method.getParameterTypes()) {
                assertFalse(parameterType.getPackageName().contains(".spi.experimental"), method::toString);
            }
        }
    }

    @Test
    void stableOnlySampleSourceHasNoExperimentalSpiImport() throws Exception {
        Path source = Path.of(System.getProperty("blendlib.projectDir"), "src", "main", "java",
                "com", "liy", "blendlib", "fixture", "StableFacadeOnlySample.java");
        assertFalse(Files.readString(source).contains(".spi.experimental"));
    }
}
