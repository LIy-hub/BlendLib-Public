package com.liy.blendlib.fabric.client.reload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.core.animation.rules.LocomotionRuleParser;
import com.liy.blendlib.core.animation.rules.LocomotionRules;
import com.liy.blendlib.core.diagnostic.BlendDiagnostic;
import com.liy.blendlib.core.diagnostic.BlendDiagnosticCodes;
import com.liy.blendlib.core.diagnostic.DiagnosticSeverity;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import org.junit.jupiter.api.Test;

/** Exercises prepare and apply through Minecraft's actual final-resource pack-selection implementation. */
class LocomotionRulesReloadTest {
    private static final BlendModelKey MODEL = BlendModelKey.parse("blendlib_showcase:fixtures/rigid_model");
    private static final Identifier DESCRIPTOR = id("blend_models/fixtures/rigid_model.json");
    private static final Identifier SIDECAR = id("blend_animation_rules/fixtures/rigid_model.json");
    private static final String IDLE = "blendlib_showcase:idle";
    private static final String WALK = "blendlib_showcase:walk";
    private static final String RUN = "blendlib_showcase:run";
    private static final String ONCE = "blendlib_showcase:once";
    private static final String NEXT = "blendlib_showcase:next";
    private static final String VALID = rules(IDLE, WALK);

    @Test
    void selectedRulesAndBackendPublishAtomicallyWithoutApplyResourceReads() throws IOException {
        MemoryPack pack = fixturePack(VALID);
        ClientModelRegistry registry = new ClientModelRegistry();
        ClientModelReloadListener listener = new ClientModelReloadListener(registry);
        ModelRegistryGeneration before = registry.current();
        try (var resources = manager(pack)) {
            var state = state(resources);
            PreparedModelGeneration prepared = listener.prepare(state);
            LocomotionRules rules = prepared.locomotionRules(MODEL).orElseThrow();
            assertSame(before, registry.current());
            assertTrue(before.locomotionRules(MODEL).isEmpty());
            assertEquals(BlendAnimationKey.parse(IDLE), rules.defaultAnimation());
            assertEquals(BlendAnimationKey.parse(WALK), rules.rules().getFirst().animation());
            assertEquals(1, pack.opens(SIDECAR));
            assertEquals(1, pack.closes(SIDECAR));
            int opens = pack.totalOpens();

            listener.apply(prepared, state);

            ModelRegistryGeneration published = registry.current();
            assertSame(rules, published.locomotionRules(MODEL).orElseThrow());
            assertSame(prepared.loadedAssets().get(MODEL), ((LoadedModelHandle) published.find(MODEL).orElseThrow()).asset());
            assertEquals(prepared.generationId(), published.find(MODEL).orElseThrow().generationId());
            assertFalse(published.find(MODEL).orElseThrow().missing());
            assertTrue(published.diagnostics().isEmpty());
            assertTrue(before.isRetired());
            assertEquals(opens, pack.totalOpens());
        }
    }

    @Test
    void missingSidecarIsSilentAndOrphanSidecarsAreNeverRead() throws IOException {
        MemoryPack pack = fixturePack(null);
        Identifier orphan = id("blend_animation_rules/missing/model.json");
        pack.put(orphan, "not JSON");
        try (var resources = manager(pack)) {
            PreparedModelGeneration prepared = new ClientModelReloadListener(new ClientModelRegistry()).prepare(state(resources));
            assertTrue(prepared.locomotionRules(MODEL).isEmpty());
            assertTrue(prepared.globalDiagnostics().isEmpty());
            assertEquals(0, pack.opens(SIDECAR));
            assertEquals(0, pack.opens(orphan));
            assertFalse(ClientModelReloadListener.createPublishedGeneration(prepared).find(MODEL).orElseThrow().missing());
        }
    }

    @Test
    void malformedUnknownDuplicateAndInvalidTargetsDisableOnlyRules() throws IOException {
        List<String> invalid = List.of(
                "{",
                rules("blendlib_showcase:missing", WALK),
                rules(IDLE, "blendlib_showcase:missing"),
                rules(ONCE, WALK),
                rules(IDLE, ONCE),
                rules(NEXT, WALK),
                rules(IDLE, NEXT),
                VALID.replace("\"schema_version\":1", "\"schema_version\":1,\"unknown\":true"),
                VALID.replace("\"schema_version\":1", "\"schema_version\":1,\"schema_version\":1"));
        for (String json : invalid) {
            MemoryPack pack = fixturePack(json);
            try (var resources = manager(pack)) {
                PreparedModelGeneration prepared = new ClientModelReloadListener(new ClientModelRegistry()).prepare(state(resources));
                assertRulesOnlyFailure(prepared);
                assertEquals(1, pack.opens(SIDECAR));
                assertEquals(1, pack.closes(SIDECAR));
            }
        }
    }

    @Test
    void sidecarForModelWithoutAnimationDeclarationOnlyDisablesRules() throws IOException {
        MemoryPack pack = fixturePack(VALID);
        String descriptor = descriptor();
        pack.put(DESCRIPTOR, descriptor.substring(0, descriptor.indexOf("\"animation\""))
                + descriptor.substring(descriptor.indexOf("\"extensions\"")));
        try (var resources = manager(pack)) {
            PreparedModelGeneration prepared = new ClientModelReloadListener(new ClientModelRegistry()).prepare(state(resources));
            assertRulesOnlyFailure(prepared);
            assertTrue(prepared.globalDiagnostics().getFirst().message().contains("no declared animation states"));
        }
    }

    @Test
    void ioFailuresHaveOneBoundedWarningWithoutDiscardingModel() throws IOException {
        MemoryPack pack = fixturePack(null);
        pack.suppliers.put(SIDECAR, () -> { throw new IOException("x".repeat(10_000)); });
        try (var resources = manager(pack)) {
            PreparedModelGeneration prepared = new ClientModelReloadListener(new ClientModelRegistry()).prepare(state(resources));
            assertRulesOnlyFailure(prepared);
            assertEquals(LocomotionRulesReload.MAX_WARNING_CHARACTERS, prepared.globalDiagnostics().getFirst().message().length());
            assertEquals("", prepared.globalDiagnostics().getFirst().causeSummary());
        }
    }

    @Test
    void oversizeReadsOnlyLimitPlusOneAndClosesStream() throws IOException {
        MemoryPack pack = fixturePack(null);
        byte[] oversized = new byte[LocomotionRuleParser.MAX_INPUT_BYTES * 2];
        java.util.Arrays.fill(oversized, (byte) ' ');
        pack.put(SIDECAR, oversized);
        try (var resources = manager(pack)) {
            PreparedModelGeneration prepared = new ClientModelReloadListener(new ClientModelRegistry()).prepare(state(resources));
            assertRulesOnlyFailure(prepared);
            assertEquals(LocomotionRuleParser.MAX_INPUT_BYTES + 1, pack.bytesRead(SIDECAR));
            assertEquals(1, pack.closes(SIDECAR));
        }
    }

    @Test
    void exactlyLimitBytesAreAccepted() throws IOException {
        String padded = VALID + " ".repeat(LocomotionRuleParser.MAX_INPUT_BYTES - VALID.getBytes(StandardCharsets.UTF_8).length);
        MemoryPack pack = fixturePack(padded);
        try (var resources = manager(pack)) {
            PreparedModelGeneration prepared = new ClientModelReloadListener(new ClientModelRegistry()).prepare(state(resources));
            assertTrue(prepared.locomotionRules(MODEL).isPresent());
            assertTrue(prepared.globalDiagnostics().isEmpty());
            assertEquals(LocomotionRuleParser.MAX_INPUT_BYTES, pack.bytesRead(SIDECAR));
            assertEquals(1, pack.closes(SIDECAR));
        }
    }

    @Test
    void realPackPrioritySelectsOnlyWinningSidecarAndNeverFallsBackOnInvalidWinner() throws IOException {
        for (String winning : List.of(rules(RUN, WALK), "{")) {
            MemoryPack lower = fixturePack(VALID);
            MemoryPack higher = new MemoryPack("higher");
            higher.put(SIDECAR, winning);
            try (var resources = manager(lower, higher)) {
                assertEquals("higher", resources.getResource(SIDECAR).orElseThrow().sourcePackId());
                PreparedModelGeneration prepared = new ClientModelReloadListener(new ClientModelRegistry()).prepare(state(resources));
                if (winning.equals("{")) {
                    assertRulesOnlyFailure(prepared);
                } else {
                    assertEquals(BlendAnimationKey.parse(RUN), prepared.locomotionRules(MODEL).orElseThrow().defaultAnimation());
                }
                assertEquals(0, lower.opens(SIDECAR));
                assertEquals(1, higher.opens(SIDECAR));
            }
        }
    }

    @Test
    void removalInvalidationRecoveryAndStaleApplyKeepExactGenerationRules() throws IOException {
        ClientModelRegistry registry = new ClientModelRegistry();
        List<Long> activeNotifications = new ArrayList<>();
        RecordingSink sink = new RecordingSink();
        ClientModelReloadListener listener = new ClientModelReloadListener(registry, activeNotifications::add,
                new ReloadDiagnosticsReporter(sink));
        PreparedModelGeneration oldPrepared;
        ModelRegistryGeneration first;
        try (var resources = manager(fixturePack(VALID))) {
            oldPrepared = listener.prepare(state(resources));
            listener.apply(oldPrepared, state(resources));
            first = registry.current();
        }
        try (var resources = manager(fixturePack(null))) {
            listener.apply(listener.prepare(state(resources)), state(resources));
            assertTrue(registry.current().locomotionRules(MODEL).isEmpty());
            assertTrue(registry.current().diagnostics().isEmpty());
            assertFalse(registry.current().find(MODEL).orElseThrow().missing());
            assertTrue(first.isRetired());
            assertTrue(first.locomotionRules(MODEL).isPresent());
        }
        try (var resources = manager(fixturePack("{"))) {
            PreparedModelGeneration invalid = listener.prepare(state(resources));
            listener.apply(invalid, state(resources));
            assertTrue(registry.current().locomotionRules(MODEL).isEmpty());
            assertFalse(registry.current().find(MODEL).orElseThrow().missing());
            assertEquals(1, sink.details.size());
            listener.apply(invalid, state(resources));
            assertEquals(1, sink.details.size(), "Repeated apply must not report a second warning");
        }
        try (var resources = manager(fixturePack(rules(RUN, WALK)))) {
            listener.apply(listener.prepare(state(resources)), state(resources));
            ModelRegistryGeneration recovered = registry.current();
            LocomotionRules rules = recovered.locomotionRules(MODEL).orElseThrow();
            assertEquals(BlendAnimationKey.parse(RUN), rules.defaultAnimation());
            assertTrue(recovered.diagnostics().isEmpty());
            int openCount = resources.listPacks().mapToInt(pack -> ((MemoryPack) pack).totalOpens()).sum();
            listener.apply(oldPrepared, state(resources));
            assertSame(recovered, registry.current());
            assertSame(rules, registry.current().locomotionRules(MODEL).orElseThrow());
            assertEquals(recovered.generationId(), activeNotifications.getLast());
            assertEquals(openCount, resources.listPacks().mapToInt(pack -> ((MemoryPack) pack).totalOpens()).sum());
            assertEquals(1, sink.details.size());
        }
    }

    @Test
    void descriptorFailureDoesNotReadItsSidecarAndBackendFailureDoesNotPublishPreparedRules() throws IOException {
        MemoryPack brokenDescriptor = fixturePack(VALID);
        brokenDescriptor.put(DESCRIPTOR, "{");
        try (var resources = manager(brokenDescriptor)) {
            PreparedModelGeneration prepared = new ClientModelReloadListener(new ClientModelRegistry()).prepare(state(resources));
            assertTrue(prepared.loadedAssets().isEmpty());
            assertEquals(0, brokenDescriptor.opens(SIDECAR));
            assertTrue(prepared.globalDiagnostics().isEmpty());
            assertTrue(ClientModelReloadListener.createPublishedGeneration(prepared).locomotionRules(MODEL).isEmpty());
        }
        MemoryPack unsupportedBackend = fixturePack(VALID);
        unsupportedBackend.put(DESCRIPTOR, descriptor().replace("\"double_sided\": false", "\"double_sided\": true"));
        try (var resources = manager(unsupportedBackend)) {
            PreparedModelGeneration prepared = new ClientModelReloadListener(new ClientModelRegistry()).prepare(state(resources));
            assertTrue(prepared.locomotionRules(MODEL).isPresent());
            ModelRegistryGeneration published = ClientModelReloadListener.createPublishedGeneration(prepared);
            assertTrue(published.find(MODEL).orElseThrow().missing());
            assertTrue(published.locomotionRules(MODEL).isEmpty());
            assertEquals(BlendDiagnosticCodes.MAT_004, published.primaryDiagnostic(MODEL).orElseThrow().code());
        }
    }

    @Test
    void constructorCopiesRuleMapsAndRejectsRulesOutsideLoadedGeneration() throws IOException {
        try (var resources = manager(fixturePack(VALID))) {
            PreparedModelGeneration original = new ClientModelReloadListener(new ClientModelRegistry()).prepare(state(resources));
            LocomotionRules rules = original.locomotionRules(MODEL).orElseThrow();
            Map<BlendModelKey, LocomotionRules> mutable = new LinkedHashMap<>(Map.of(MODEL, rules));
            var prepared = new PreparedModelGeneration(original.generationId(), original.loadedAssets(), Map.of(), mutable, List.of());
            var published = ClientModelReloadListener.createPublishedGeneration(prepared);
            var copied = new ModelRegistryGeneration(published.generationId(), published.handles(), Map.of(), List.of(), mutable);
            mutable.clear();
            assertSame(rules, prepared.locomotionRules(MODEL).orElseThrow());
            assertSame(rules, copied.locomotionRules(MODEL).orElseThrow());
            assertThrows(IllegalArgumentException.class, () -> new PreparedModelGeneration(1, Map.of(), Map.of(), Map.of(MODEL, rules), List.of()));
            assertThrows(IllegalArgumentException.class, () -> new ModelRegistryGeneration(1, Map.of(), Map.of(), List.of(), Map.of(MODEL, rules)));
            assertThrows(IllegalArgumentException.class, () -> new ModelRegistryGeneration(1,
                    Map.of(MODEL, MissingModelHandle.notDiscovered(MODEL, 1)), Map.of(), List.of(), Map.of(MODEL, rules)));
            assertTrue(new PreparedModelGeneration(1, original.loadedAssets(), Map.of(), List.of()).locomotionRules(MODEL).isEmpty());
            assertTrue(new ModelRegistryGeneration(1, published.handles(), Map.of(), List.of()).locomotionRules(MODEL).isEmpty());
        }
    }

    private static void assertRulesOnlyFailure(PreparedModelGeneration prepared) {
        assertTrue(prepared.loadedAssets().containsKey(MODEL));
        assertTrue(prepared.primaryDiagnostics().isEmpty());
        assertTrue(prepared.locomotionRules(MODEL).isEmpty());
        assertEquals(1, prepared.globalDiagnostics().size());
        BlendDiagnostic warning = prepared.globalDiagnostics().getFirst();
        assertEquals(DiagnosticSeverity.WARN, warning.severity());
        assertEquals(LocomotionRulesReload.DIAGNOSTIC_CODE, warning.code());
        assertEquals(MODEL.resourceId(), warning.modelKey());
        assertEquals(SIDECAR.toString(), warning.resourceId().value());
        assertTrue(warning.message().length() <= LocomotionRulesReload.MAX_WARNING_CHARACTERS);
        ModelRegistryGeneration published = ClientModelReloadListener.createPublishedGeneration(prepared);
        assertFalse(published.find(MODEL).orElseThrow().missing());
        assertTrue(published.primaryDiagnostic(MODEL).isEmpty());
        assertTrue(published.locomotionRules(MODEL).isEmpty());
        assertEquals(List.of(warning), published.diagnostics());
    }

    private static String rules(String defaultState, String target) {
        return "{\"schema_version\":1,\"default\":\"" + defaultState + "\",\"rules\":[{\"animation\":\"" + target
                + "\",\"conditions\":[{\"input\":\"grounded\",\"equals\":true},"
                + "{\"input\":\"speed\",\"enter_min\":0.2,\"exit_min\":0.1}]}]}";
    }

    private static MemoryPack fixturePack(String sidecar) throws IOException {
        MemoryPack pack = new MemoryPack("fixture");
        pack.put(DESCRIPTOR, descriptor());
        pack.put(id("models3d/fixtures/rigid_model.glb"), fixtureBytes("models3d/fixtures/rigid_model.glb"));
        pack.put(id("textures/blendlib/fixtures_rigid_model__rigidsurface.png"), new byte[0]);
        if (sidecar != null) pack.put(SIDECAR, sidecar);
        return pack;
    }

    private static String descriptor() throws IOException {
        String fixture = new String(fixtureBytes("blend_models/fixtures/rigid_model.json"), StandardCharsets.UTF_8);
        int start = fixture.indexOf("\"animation\"");
        int end = fixture.indexOf("\"extensions\"");
        String states = "\"animation\":{\"initial_state\":\"" + IDLE + "\",\"states\":{";
        states += stateJson(IDLE, true, null) + "," + stateJson(WALK, true, null) + "," + stateJson(RUN, true, null)
                + "," + stateJson(ONCE, false, null) + "," + stateJson(NEXT, true, IDLE) + "}},";
        return fixture.substring(0, start) + states + fixture.substring(end);
    }

    private static String stateJson(String key, boolean loop, String next) {
        return "\"" + key + "\":{\"clip\":\"rigid_pulse\",\"loop\":" + loop + ",\"speed\":1"
                + (next == null ? "" : ",\"next\":\"" + next + "\"") + "}";
    }

    private static byte[] fixtureBytes(String path) throws IOException {
        return Files.readAllBytes(Path.of(System.getProperty("blendlib.projectDir")).getParent()
                .resolve("blendlib-showcase/src/main/resources/assets/blendlib_showcase").resolve(path));
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("blendlib_showcase", path);
    }

    private static MultiPackResourceManager manager(MemoryPack... packs) {
        return new MultiPackResourceManager(PackType.CLIENT_RESOURCES, List.of(packs));
    }

    private static PreparableReloadListener.SharedState state(MultiPackResourceManager resources) {
        return new PreparableReloadListener.SharedState(resources);
    }

    private static final class RecordingSink implements ReloadDiagnosticsReporter.Sink {
        private final List<ReloadDiagnosticsReporter.Detail> details = new ArrayList<>();
        @Override public void reportSummary(ReloadDiagnosticsReporter.Summary summary) { }
        @Override public boolean developmentDetailsEnabled() { return true; }
        @Override public void reportDevelopmentDetail(ReloadDiagnosticsReporter.Detail detail) { details.add(detail); }
    }

    /** Instrumented pack streams, with Minecraft itself performing final pack selection. */
    private static final class MemoryPack implements PackResources {
        private final PackLocationInfo location;
        private final Map<Identifier, IoSupplier<InputStream>> suppliers = new LinkedHashMap<>();
        private final Map<Identifier, AtomicInteger> openCounts = new LinkedHashMap<>();
        private final Map<Identifier, AtomicInteger> closeCounts = new LinkedHashMap<>();
        private final Map<Identifier, AtomicInteger> byteCounts = new LinkedHashMap<>();

        private MemoryPack(String name) {
            location = new PackLocationInfo(name, Component.literal(name), PackSource.DEFAULT, Optional.empty());
        }

        private void put(Identifier id, String text) { put(id, text.getBytes(StandardCharsets.UTF_8)); }
        private void put(Identifier id, byte[] source) {
            byte[] bytes = source.clone();
            suppliers.put(id, () -> {
                openCounts.computeIfAbsent(id, ignored -> new AtomicInteger()).incrementAndGet();
                InputStream delegate = new ByteArrayInputStream(bytes);
                return new InputStream() {
                    @Override public int read() throws IOException {
                        int value = delegate.read();
                        if (value >= 0) byteCounts.computeIfAbsent(id, ignored -> new AtomicInteger()).incrementAndGet();
                        return value;
                    }
                    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                        int count = delegate.read(buffer, offset, length);
                        if (count > 0) byteCounts.computeIfAbsent(id, ignored -> new AtomicInteger()).addAndGet(count);
                        return count;
                    }
                    @Override public void close() throws IOException {
                        closeCounts.computeIfAbsent(id, ignored -> new AtomicInteger()).incrementAndGet();
                        delegate.close();
                    }
                };
            });
        }

        private int opens(Identifier id) { return openCounts.getOrDefault(id, new AtomicInteger()).get(); }
        private int closes(Identifier id) { return closeCounts.getOrDefault(id, new AtomicInteger()).get(); }
        private int bytesRead(Identifier id) { return byteCounts.getOrDefault(id, new AtomicInteger()).get(); }
        private int totalOpens() { return openCounts.values().stream().mapToInt(AtomicInteger::get).sum(); }
        @Override public IoSupplier<InputStream> getRootResource(String... names) { return null; }
        @Override public IoSupplier<InputStream> getResource(PackType type, Identifier id) { return suppliers.get(id); }
        @Override public void listResources(PackType type, String namespace, String path, ResourceOutput output) {
            suppliers.forEach((id, supplier) -> {
                if (id.getNamespace().equals(namespace) && id.getPath().startsWith(path + "/")) output.accept(id, supplier);
            });
        }
        @Override public Set<String> getNamespaces(PackType type) { return Set.of("blendlib_showcase"); }
        @Override public <T> T getMetadataSection(MetadataSectionType<T> type) { return null; }
        @Override public PackLocationInfo location() { return location; }
        @Override public void close() { }
    }
}
