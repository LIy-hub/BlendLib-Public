package com.liy.blendlib.showcase.x4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.fabric.client.api.BlendRenderer;
import com.liy.blendlib.fabric.client.api.ClientDiagnostic;
import com.liy.blendlib.fabric.client.api.ClientDiagnosticSeverity;
import com.liy.blendlib.fabric.client.api.ClientModelLookup;
import com.liy.blendlib.fabric.client.api.ClientModelView;
import com.liy.blendlib.fabric.client.api.ClientRegistryView;
import com.liy.blendlib.fabric.client.host.X4ExperimentalAccess;
import com.liy.blendlib.fabric.client.host.X4PreparedSnapshot;
import com.liy.blendlib.fabric.client.render.MissingModelRenderHandle;
import com.liy.blendlib.fabric.client.render.RenderSubmissionContext;
import com.liy.blendlib.spi.experimental.BlendProvider;
import com.liy.blendlib.spi.experimental.CapabilityOffer;
import com.liy.blendlib.spi.experimental.CapabilityRegistry;
import com.liy.blendlib.spi.experimental.CapabilityRequest;
import com.liy.blendlib.spi.experimental.CapabilityVersion;
import com.liy.blendlib.spi.experimental.CapabilityVersionRange;
import com.liy.blendlib.spi.experimental.ProviderLifecycleSession;
import com.liy.blendlib.showcase.client.x4.X4ShowcaseCatalog;
import com.liy.blendlib.showcase.client.x4.X4ShowcaseFixture;
import com.mojang.blaze3d.vertex.PoseStack;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.junit.jupiter.api.Test;

/** Executes every Showcase X4 fixture through the public snapshot-only rendering seam. */
class X4ShowcaseCatalogTest {
    @Test
    void formalAndExplicitExperimentalShowcaseFixturesPrepareAndSubmitWithoutRegistryWorkOnSubmit() throws IOException {
        FakeLookup lookup = new FakeLookup();
        ProviderLifecycleSession generationSession = publishedSession(1L);
        AtomicInteger submits = new AtomicInteger();
        BlendRenderer renderer = new BlendRenderer((snapshot, context) -> submits.incrementAndGet());
        List<X4ShowcaseFixture<?>> fixtures = new ArrayList<>(
                X4ShowcaseCatalog.formalFixtures(lookup, renderer, generationSession));
        fixtures.addAll(X4ShowcaseCatalog.experimentalFixtures(
                X4ExperimentalAccess.optIn(BlendResourceId.parse("blendlib_showcase:x4-fixture-opt-in")),
                lookup,
                renderer,
                generationSession));

        assertEquals(expectedFixtureIds(), fixtures.stream().map(X4ShowcaseFixture::id).toList());
        for (X4ShowcaseFixture<?> fixture : fixtures) {
            fixture.freeze();
            try (X4PreparedSnapshot prepared = fixture.prepare()) {
                int beforeSubmitLookups = lookup.resolveCalls.get();
                fixture.submit(prepared, unusedContext());
                assertEquals(beforeSubmitLookups, lookup.resolveCalls.get(), fixture.id());
            }
        }
        assertEquals(10, fixtures.size());
        assertEquals(10, submits.get());
        assertEquals(10, lookup.resolveCalls.get());
        assertThrows(IllegalStateException.class,
                () -> X4ShowcaseCatalog.experimentalFixtures(
                        X4ExperimentalAccess.disabled(), lookup, renderer, generationSession));
        generationSession.retire();
    }

    private static List<String> expectedFixtureIds() throws IOException {
        try (var stream = X4ShowcaseCatalogTest.class.getResourceAsStream("/x4/host-fixtures.txt")) {
            assertTrue(stream != null, "X4 Showcase fixture matrix resource must be packaged with the test");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
        }
    }

    private static RenderSubmissionContext unusedContext() {
        SubmitNodeCollector collector = (SubmitNodeCollector) Proxy.newProxyInstance(
                X4ShowcaseCatalogTest.class.getClassLoader(),
                new Class<?>[] {SubmitNodeCollector.class},
                (proxy, method, arguments) -> {
                    throw new AssertionError("Showcase fixture backend must not use collector method " + method.getName());
                });
        return new RenderSubmissionContext(new PoseStack(), collector);
    }

    private static ProviderLifecycleSession publishedSession(long generation) {
        BlendResourceId providerId = BlendResourceId.parse("blendlib_showcase:x4-test-provider");
        BlendResourceId capabilityId = BlendResourceId.parse("blendlib_showcase:x4-test-capability");
        BlendProvider provider = new BlendProvider() {
            @Override
            public BlendResourceId providerId() {
                return providerId;
            }

            @Override
            public java.util.Collection<CapabilityOffer> offers() {
                return List.of(new CapabilityOffer(
                        providerId, capabilityId, CapabilityVersion.INITIAL_PROTOCOL, 1));
            }
        };
        CapabilityRegistry registry = new CapabilityRegistry();
        registry.register(provider);
        registry.discover(List.of(CapabilityRequest.required(
                capabilityId,
                new CapabilityVersionRange(CapabilityVersion.INITIAL_PROTOCOL, new CapabilityVersion(2, 0, 0)))));
        ProviderLifecycleSession session = new ProviderLifecycleSession(registry.freeze(generation), List.of(provider));
        assertTrue(session.prepare().successful());
        assertTrue(session.apply().successful());
        session.publish();
        return session;
    }

    private static final class FakeLookup implements ClientModelLookup {
        private final AtomicInteger resolveCalls = new AtomicInteger();
        private final ClientModelView view;

        private FakeLookup() {
            BlendModelKey key = X4ShowcaseCatalog.MODEL_KEY;
            view = new ClientModelView(key, 1L, false, new MissingModelRenderHandle(key, 1L), Optional.of(new ClientDiagnostic(
                    ClientDiagnosticSeverity.ERROR,
                    "X4-SHOWCASE-MISSING",
                    key.resourceId(),
                    key.resourceId(),
                    "x4-showcase-test",
                    "fixture missing model",
                    "none")));
        }

        @Override
        public ClientRegistryView snapshot() {
            return new ClientRegistryView(view.generationId(), Map.of(view.key(), view), List.of());
        }

        @Override
        public ClientModelView resolve(BlendModelKey key) {
            assertEquals(view.key(), key);
            resolveCalls.incrementAndGet();
            return view;
        }
    }
}
