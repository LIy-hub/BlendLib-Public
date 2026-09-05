package com.liy.blendlib.fabric.client;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.textures.GpuTextureView;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import org.junit.jupiter.api.Test;

class X7Minecraft2612PassOwnerHostTest {
    @Test
    void registrarSeamInstallsBothExactFabricPhasesOnlyOnce() throws Exception {
        FakeRegistrar registrar = new FakeRegistrar();
        X7Minecraft2612PassOwnerHost host = new X7Minecraft2612PassOwnerHost(
                registrar, targetScope(() -> { }, () -> new X7Minecraft2612TargetScope.TargetViews(view(), view())),
                (phase, color, depth) -> { });

        host.install();
        host.install();

        assertEquals(1, registrar.afterSolidRegistrations);
        assertEquals(1, registrar.beforeTranslucentRegistrations);
        assertTrue(registrar.afterSolid != null);
        assertTrue(registrar.beforeTranslucent != null);
        assertEquals(
                void.class,
                LevelRenderEvents.AfterSolidFeatures.class
                        .getMethod("afterSolidFeatures", LevelRenderContext.class)
                        .getReturnType());
        assertEquals(
                void.class,
                LevelRenderEvents.BeforeTranslucentTerrain.class
                        .getMethod("beforeTranslucentTerrain", LevelRenderContext.class)
                        .getReturnType());
    }

    @Test
    void eachRegisteredPhaseAssertsThenResolvesAndUsesTheTargetSynchronously() {
        FakeRegistrar registrar = new FakeRegistrar();
        List<String> order = new ArrayList<>();
        FakeTextureView color = view();
        FakeTextureView depth = view();
        X7Minecraft2612PassOwnerHost host = new X7Minecraft2612PassOwnerHost(
                registrar,
                targetScope(
                        () -> order.add("assert"),
                        () -> {
                            order.add("target");
                            return new X7Minecraft2612TargetScope.TargetViews(color, depth);
                        }),
                (phase, actualColor, actualDepth) -> {
                    order.add("scope:" + phase);
                    assertSame(color, actualColor);
                    assertSame(depth, actualDepth);
                });
        host.install();

        LevelRenderContext context = contextToken();
        registrar.afterSolid.afterSolidFeatures(context);
        registrar.beforeTranslucent.beforeTranslucentTerrain(context);

        assertEquals(List.of(
                "assert",
                "target",
                "scope:AFTER_SOLID_FEATURES",
                "assert",
                "target",
                "scope:BEFORE_TRANSLUCENT_TERRAIN"), order);
    }

    @Test
    void nullTargetViewsAndFailuresFailClosedWithoutEscapingTheFabricCallback() {
        FakeRegistrar registrar = new FakeRegistrar();
        int[] scopeCalls = {0};
        X7Minecraft2612PassOwnerHost host = new X7Minecraft2612PassOwnerHost(
                registrar,
                targetScope(() -> { }, () -> null),
                (phase, color, depth) -> scopeCalls[0]++);
        host.install();

        assertDoesNotThrow(() -> registrar.afterSolid.afterSolidFeatures(contextToken()));
        assertEquals(0, scopeCalls[0]);

        FakeRegistrar failingRegistrar = new FakeRegistrar();
        X7Minecraft2612PassOwnerHost failingHost = new X7Minecraft2612PassOwnerHost(
                failingRegistrar,
                targetScope(() -> { }, () -> new X7Minecraft2612TargetScope.TargetViews(view(), view())),
                (phase, color, depth) -> {
                    throw new IllegalStateException("scope failure must fail closed");
                });
        failingHost.install();

        assertDoesNotThrow(() -> failingRegistrar.afterSolid.afterSolidFeatures(contextToken()));
    }

    @Test
    void targetScopeReturnsDistinctAbsentAndWorkFailureOutcomes() {
        X7Minecraft2612TargetScope absent = targetScope(() -> { }, () -> null);
        assertEquals(
                X7Minecraft2612TargetScope.TargetOutcome.TARGET_ABSENT,
                absent.withCurrentMainTarget(X7Minecraft2612PassOwnerHost.Phase.AFTER_SOLID_FEATURES, (phase, color, depth) -> { }));

        X7Minecraft2612TargetScope failing = targetScope(
                () -> { }, () -> new X7Minecraft2612TargetScope.TargetViews(view(), view()));
        assertEquals(
                X7Minecraft2612TargetScope.TargetOutcome.TARGET_WORK_FAILED,
                failing.withCurrentMainTarget(
                        X7Minecraft2612PassOwnerHost.Phase.AFTER_SOLID_FEATURES,
                        (phase, color, depth) -> {
                            throw new IllegalStateException("target work failure");
                        }));
    }

    @Test
    void hostAndPortsStayPackagePrivateAndNoTargetOrPassObjectIsRetained() throws IOException {
        assertFalse(Modifier.isPublic(X7Minecraft2612PassOwnerHost.class.getModifiers()));
        assertFalse(Modifier.isPublic(X7Minecraft2612TargetScope.class.getModifiers()));
        assertTrue(Arrays.stream(X7Minecraft2612PassOwnerHost.class.getDeclaredConstructors())
                .noneMatch(constructor -> Modifier.isPublic(constructor.getModifiers())));
        assertTrue(Arrays.stream(X7Minecraft2612PassOwnerHost.class.getDeclaredMethods())
                .noneMatch(method -> Modifier.isPublic(method.getModifiers())));
        for (Class<?> nested : X7Minecraft2612PassOwnerHost.class.getDeclaredClasses()) {
            assertFalse(Modifier.isPublic(nested.getModifiers()), nested.getName());
        }
        for (Class<?> fieldType : Arrays.stream(X7Minecraft2612TargetScope.class.getDeclaredFields())
                .map(field -> field.getType())
                .toList()) {
            assertFalse(fieldType == LevelRenderContext.class, fieldType.getName());
            assertFalse(fieldType == GpuTextureView.class, fieldType.getName());
        }

        Path projectDir = Path.of(System.getProperty("blendlib.projectDir"));
        String hostSource = Files.readString(projectDir.resolve(
                "src/client/java/com/liy/blendlib/fabric/client/X7Minecraft2612PassOwnerHost.java"));
        String targetScopeSource = Files.readString(projectDir.resolve(
                "src/client/java/com/liy/blendlib/fabric/client/X7Minecraft2612TargetScope.java"));
        assertTrue(hostSource.contains("LevelRenderEvents.AFTER_SOLID_FEATURES.register"));
        assertTrue(hostSource.contains("LevelRenderEvents.BEFORE_TRANSLUCENT_TERRAIN.register"));
        assertTrue(targetScopeSource.contains("RenderSystem::assertOnRenderThread"));
        assertTrue(targetScopeSource.contains("Minecraft.getInstance().getMainRenderTarget()"));
        for (String forbidden : List.of(
                "org.spongepowered.asm.mixin",
                "createRenderPass(",
                "drawIndexed(")) {
            assertFalse(hostSource.contains(forbidden), forbidden);
            assertFalse(targetScopeSource.contains(forbidden), forbidden);
        }
    }

    private static X7Minecraft2612TargetScope targetScope(
            X7Minecraft2612TargetScope.RenderThreadAssertion assertion,
            X7Minecraft2612TargetScope.MainTargetResolver resolver) {
        return new X7Minecraft2612TargetScope(assertion, resolver);
    }

    private static FakeTextureView view() {
        return new FakeTextureView();
    }

    private static LevelRenderContext contextToken() {
        return (LevelRenderContext) Proxy.newProxyInstance(
                LevelRenderContext.class.getClassLoader(),
                new Class<?>[] {LevelRenderContext.class},
                (proxy, method, arguments) -> null);
    }

    private static final class FakeTextureView extends GpuTextureView {
        private boolean closed;

        private FakeTextureView() {
            super(null, 0, 1);
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        public boolean isClosed() {
            return closed;
        }
    }

    private static final class FakeRegistrar implements X7Minecraft2612PassOwnerHost.EventRegistrar {
        private int afterSolidRegistrations;
        private int beforeTranslucentRegistrations;
        private LevelRenderEvents.AfterSolidFeatures afterSolid;
        private LevelRenderEvents.BeforeTranslucentTerrain beforeTranslucent;

        @Override
        public void registerAfterSolidFeatures(LevelRenderEvents.AfterSolidFeatures callback) {
            afterSolidRegistrations++;
            afterSolid = callback;
        }

        @Override
        public void registerBeforeTranslucentTerrain(LevelRenderEvents.BeforeTranslucentTerrain callback) {
            beforeTranslucentRegistrations++;
            beforeTranslucent = callback;
        }
    }
}
