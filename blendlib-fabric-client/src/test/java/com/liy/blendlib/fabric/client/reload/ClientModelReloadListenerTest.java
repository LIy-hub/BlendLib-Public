package com.liy.blendlib.fabric.client.reload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.AnimationChannel;
import com.liy.blendlib.core.animation.AnimationClip;
import com.liy.blendlib.core.animation.AnimationPath;
import com.liy.blendlib.core.animation.Interpolation;
import com.liy.blendlib.core.animation.runtime.AnimationControllerDefinition;
import com.liy.blendlib.core.animation.runtime.AnimationState;
import com.liy.blendlib.core.animation.runtime.LocalPose;
import com.liy.blendlib.core.diagnostic.BlendAssetLoadException;
import com.liy.blendlib.core.diagnostic.BlendDiagnostic;
import com.liy.blendlib.core.diagnostic.BlendDiagnosticCodes;
import com.liy.blendlib.core.diagnostic.DiagnosticSeverity;
import com.liy.blendlib.core.limits.BlendAssetLimits;
import com.liy.blendlib.fabric.client.animation.ClientAnimationInstanceRegistry;
import com.liy.blendlib.fabric.client.animation.PoseCacheKey;
import com.liy.blendlib.fabric.client.render.StaticRigidRenderHandle;
import java.io.ByteArrayInputStream;
import java.io.IOException;
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
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.junit.jupiter.api.Test;

class ClientModelReloadListenerTest {
    private static final Identifier DESCRIPTOR_ID = Identifier.fromNamespaceAndPath(
            "blendlib_showcase", "blend_models/fixtures/static_model.json");
    private static final Identifier MESH_ID = Identifier.fromNamespaceAndPath(
            "blendlib_showcase", "models3d/fixtures/static_model.glb");
    private static final Identifier TEXTURE_ID = Identifier.fromNamespaceAndPath(
            "blendlib_showcase", "textures/blendlib/fixtures_static_model__staticsurface.png");
    private static final BlendModelKey MODEL_KEY = BlendModelKey.parse("blendlib_showcase:fixtures/static_model");
    private static final BlendAnimationKey RETIREMENT_ANIMATION_KEY = BlendAnimationKey.parse("blendlib:reload_retirement");
    private static final AnimationControllerDefinition RETIREMENT_DEFINITION = retirementDefinition();

    @Test
    void prepareUsesOnlyFinalResourceManagerSelectionThenApplyPublishesBackendReadyGeneration() throws IOException {
        AtomicInteger descriptorOpens = new AtomicInteger();
        AtomicInteger meshOpens = new AtomicInteger();
        AtomicInteger textureOpens = new AtomicInteger();
        Map<Identifier, Resource> finalResources = fixtureResources(
                showcaseBytes("blend_models/fixtures/static_model.json"), descriptorOpens, meshOpens, textureOpens, true, true);
        FinalResourceManager resourceManager = new FinalResourceManager(finalResources);
        ClientModelRegistry registry = new ClientModelRegistry();
        ModelRegistryGeneration before = registry.current();
        ClientModelReloadListener listener = new ClientModelReloadListener(registry);

        PreparedModelGeneration prepared = listener.prepare(sharedState(resourceManager));

        assertSame(before, registry.current());
        assertEquals(1L, prepared.generationId());
        assertEquals(List.of(MODEL_KEY), List.copyOf(prepared.loadedAssets().keySet()));
        assertTrue(prepared.primaryDiagnostics().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> prepared.loadedAssets().clear());
        assertEquals(1, resourceManager.listResourcesCalls.get());
        assertEquals(0, resourceManager.getResourceStackCalls.get());
        assertEquals(1, descriptorOpens.get());
        assertEquals(1, meshOpens.get());
        assertEquals(0, textureOpens.get());

        listener.apply(prepared, sharedState(resourceManager));

        assertSame(prepared.loadedAssets().get(MODEL_KEY), ((LoadedModelHandle) registry.find(MODEL_KEY).orElseThrow()).asset());
        ModelHandle published = registry.find(MODEL_KEY).orElseThrow();
        assertTrue(published instanceof LoadedModelHandle);
        assertTrue(published.renderHandle() instanceof StaticRigidRenderHandle);
        assertFalse(published.renderHandle().missingModel());
        assertTrue(before.isRetired());
        assertEquals(1, descriptorOpens.get());
        assertEquals(1, meshOpens.get());
    }

    @Test
    void twentyPrepareApplyCyclesReleaseOldRegistryOwnershipAndKeepAStaleCandidateBounded() throws IOException {
        AtomicInteger descriptorOpens = new AtomicInteger();
        AtomicInteger meshOpens = new AtomicInteger();
        AtomicInteger textureOpens = new AtomicInteger();
        FinalResourceManager resourceManager = new FinalResourceManager(fixtureResources(
                showcaseBytes("blend_models/fixtures/static_model.json"),
                descriptorOpens,
                meshOpens,
                textureOpens,
                true,
                true));
        ClientModelRegistry registry = new ClientModelRegistry();
        List<Long> publishedGenerations = new ArrayList<>();
        ClientModelReloadListener listener = new ClientModelReloadListener(registry, publishedGenerations::add);
        PreparableReloadListener.SharedState state = sharedState(resourceManager);
        ModelRegistryGeneration previous = registry.current();
        PreparedModelGeneration firstPrepared = null;

        for (long cycle = 1L; cycle <= 20L; cycle++) {
            PreparedModelGeneration prepared = listener.prepare(state);
            if (firstPrepared == null) {
                firstPrepared = prepared;
            }

            assertEquals(cycle, prepared.generationId());
            listener.apply(prepared, state);

            ReloadRetentionMetrics metrics = registry.reloadRetentionMetrics();
            assertEquals(cycle, registry.current().generationId());
            assertTrue(previous.isRetired());
            assertEquals(cycle, metrics.activeGenerationId());
            assertEquals(1, metrics.activeBackendHandleCount());
            assertEquals(1, metrics.activeLoadedBackendHandleCount());
            assertEquals(0, metrics.activeMissingBackendHandleCount());
            assertEquals(0, metrics.registryRetainedRetiredBackendHandleCount());
            assertEquals(cycle == 1L ? 0 : 1, metrics.mostRecentlyRetiredBackendHandleCount());
            assertEquals(cycle, metrics.retiredGenerationCount());
            assertEquals(0L, metrics.staleGenerationCount());
            assertEquals(1, metrics.peakActiveBackendHandleCount());
            assertEquals(cycle, descriptorOpens.get());
            assertEquals(cycle, meshOpens.get());
            previous = registry.current();
        }

        assertEquals(List.of(
                1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L,
                11L, 12L, 13L, 14L, 15L, 16L, 17L, 18L, 19L, 20L), publishedGenerations);
        assertEquals(0, textureOpens.get());

        ModelRegistryGeneration activeBeforeStaleApply = registry.current();
        listener.apply(firstPrepared, state);

        ReloadRetentionMetrics afterStaleApply = registry.reloadRetentionMetrics();
        assertSame(activeBeforeStaleApply, registry.current());
        assertEquals(20L, afterStaleApply.activeGenerationId());
        assertEquals(1, afterStaleApply.activeBackendHandleCount());
        assertEquals(0, afterStaleApply.registryRetainedRetiredBackendHandleCount());
        assertEquals(1, afterStaleApply.mostRecentlyRetiredBackendHandleCount());
        assertEquals(21L, afterStaleApply.retiredGenerationCount());
        assertEquals(1L, afterStaleApply.staleGenerationCount());
        assertEquals(1, afterStaleApply.peakActiveBackendHandleCount());
        assertEquals(20L, publishedGenerations.getLast());
    }

    @Test
    void resourcePackFinalSelectionLoadsHighPriorityDescriptorWithBaselineRigidResources() throws IOException {
        AtomicInteger highPriorityDescriptorOpens = new AtomicInteger();
        AtomicInteger lowPriorityDescriptorOpens = new AtomicInteger();
        AtomicInteger rigidMeshOpens = new AtomicInteger();
        AtomicInteger rigidTextureOpens = new AtomicInteger();
        Map<Identifier, Resource> finalResources = new LinkedHashMap<>();
        finalResources.put(
                DESCRIPTOR_ID,
                resource(p4ResourcePackBytes(
                        "valid-override", "assets/blendlib_showcase/blend_models/fixtures/static_model.json"), highPriorityDescriptorOpens));
        finalResources.put(
                Identifier.fromNamespaceAndPath("blendlib_showcase", "models3d/fixtures/rigid_model.glb"),
                resource(showcaseBytes("models3d/fixtures/rigid_model.glb"), rigidMeshOpens));
        finalResources.put(
                Identifier.fromNamespaceAndPath(
                        "blendlib_showcase", "textures/blendlib/fixtures_rigid_model__rigidsurface.png"),
                resource(showcaseBytes("textures/blendlib/fixtures_rigid_model__rigidsurface.png"), rigidTextureOpens));
        FinalResourceManager resourceManager = new FinalResourceManager(
                finalResources,
                Map.of(DESCRIPTOR_ID, resource(
                        showcaseBytes("blend_models/fixtures/static_model.json"), lowPriorityDescriptorOpens)));
        ClientModelRegistry registry = new ClientModelRegistry();
        ClientModelReloadListener listener = new ClientModelReloadListener(registry);

        PreparedModelGeneration prepared = listener.prepare(sharedState(resourceManager));

        assertEquals(List.of(MODEL_KEY), List.copyOf(prepared.loadedAssets().keySet()));
        assertTrue(prepared.primaryDiagnostics().isEmpty());
        assertTrue(prepared.loadedAssets().get(MODEL_KEY).materials().containsKey("RigidSurface"));
        assertFalse(prepared.loadedAssets().get(MODEL_KEY).materials().containsKey("StaticSurface"));
        assertEquals(1, highPriorityDescriptorOpens.get());
        assertEquals(0, lowPriorityDescriptorOpens.get());
        assertEquals(1, rigidMeshOpens.get());
        assertEquals(0, rigidTextureOpens.get());
        assertEquals(1, resourceManager.getResourceCalls(
                Identifier.fromNamespaceAndPath("blendlib_showcase", "models3d/fixtures/rigid_model.glb")));
        assertEquals(1, resourceManager.getResourceCalls(Identifier.fromNamespaceAndPath(
                "blendlib_showcase", "textures/blendlib/fixtures_rigid_model__rigidsurface.png")));
        assertEquals(0, resourceManager.getResourceStackCalls.get());

        listener.apply(prepared, sharedState(resourceManager));

        ModelHandle handle = registry.find(MODEL_KEY).orElseThrow();
        assertTrue(handle instanceof LoadedModelHandle);
        LoadedModelHandle loaded = (LoadedModelHandle) handle;
        assertTrue(loaded.asset().materials().containsKey("RigidSurface"));
        assertEquals(
                "blendlib_showcase:textures/blendlib/fixtures_rigid_model__rigidsurface.png",
                loaded.asset().materials().get("RigidSurface").baseColor().value());
        assertFalse(handle.missing());
        assertEquals(0, lowPriorityDescriptorOpens.get());
    }

    @Test
    void resourcePackFinalSelectionKeepsMalformedHighPriorityDescriptorAsOneMissingModel() throws IOException {
        AtomicInteger highPriorityDescriptorOpens = new AtomicInteger();
        AtomicInteger lowPriorityDescriptorOpens = new AtomicInteger();
        AtomicInteger staticMeshOpens = new AtomicInteger();
        AtomicInteger staticTextureOpens = new AtomicInteger();
        Map<Identifier, Resource> finalResources = new LinkedHashMap<>();
        finalResources.put(
                DESCRIPTOR_ID,
                resource(p4ResourcePackBytes(
                        "malformed-missing-mesh", "assets/blendlib_showcase/blend_models/fixtures/static_model.json"), highPriorityDescriptorOpens));
        finalResources.put(MESH_ID, resource(showcaseBytes("models3d/fixtures/static_model.glb"), staticMeshOpens));
        finalResources.put(TEXTURE_ID, resource(
                showcaseBytes("textures/blendlib/fixtures_static_model__staticsurface.png"), staticTextureOpens));
        FinalResourceManager resourceManager = new FinalResourceManager(
                finalResources,
                Map.of(DESCRIPTOR_ID, resource(
                        showcaseBytes("blend_models/fixtures/static_model.json"), lowPriorityDescriptorOpens)));
        ClientModelRegistry registry = new ClientModelRegistry();
        ClientModelReloadListener listener = new ClientModelReloadListener(registry);

        PreparedModelGeneration prepared = listener.prepare(sharedState(resourceManager));

        assertTrue(prepared.loadedAssets().isEmpty());
        assertEquals(1, prepared.primaryDiagnostics().size());
        assertEquals(BlendDiagnosticCodes.DESC_002, prepared.primaryDiagnostics().get(MODEL_KEY).code());
        assertEquals("blendlib_showcase:models3d/fixtures/does_not_exist.glb",
                prepared.primaryDiagnostics().get(MODEL_KEY).resourceId().value());
        assertEquals("/mesh", prepared.primaryDiagnostics().get(MODEL_KEY).location());
        assertEquals(1, highPriorityDescriptorOpens.get());
        assertEquals(0, lowPriorityDescriptorOpens.get());
        assertEquals(0, staticMeshOpens.get());
        assertEquals(0, staticTextureOpens.get());
        assertEquals(1, resourceManager.getResourceCalls(
                Identifier.fromNamespaceAndPath("blendlib_showcase", "models3d/fixtures/does_not_exist.glb")));
        assertEquals(0, resourceManager.getResourceCalls(MESH_ID));
        assertEquals(0, resourceManager.getResourceStackCalls.get());

        listener.apply(prepared, sharedState(resourceManager));

        ModelHandle missing = registry.find(MODEL_KEY).orElseThrow();
        assertTrue(missing instanceof MissingModelHandle);
        assertTrue(missing.missing());
        assertTrue(missing.renderHandle().missingModel());
        assertEquals(1, registry.current().diagnostics().size());
        assertEquals(prepared.primaryDiagnostics().get(MODEL_KEY), registry.current().primaryDiagnostic(MODEL_KEY).orElseThrow());
        assertEquals(0, lowPriorityDescriptorOpens.get());
    }

    @Test
    void missingMeshProducesOnePrimaryDiagnosticAndABackendReadyMissingHandle() throws IOException {
        AtomicInteger descriptorOpens = new AtomicInteger();
        FinalResourceManager resourceManager = new FinalResourceManager(fixtureResources(
                showcaseBytes("blend_models/fixtures/static_model.json"), descriptorOpens, new AtomicInteger(), new AtomicInteger(), false, false));
        ClientModelRegistry registry = new ClientModelRegistry();
        ClientModelReloadListener listener = new ClientModelReloadListener(registry);

        PreparedModelGeneration prepared = listener.prepare(sharedState(resourceManager));

        assertTrue(prepared.loadedAssets().isEmpty());
        assertEquals(1, prepared.primaryDiagnostics().size());
        assertEquals(BlendDiagnosticCodes.DESC_002, prepared.primaryDiagnostics().get(MODEL_KEY).code());
        listener.apply(prepared, sharedState(resourceManager));

        ModelHandle missing = registry.find(MODEL_KEY).orElseThrow();
        assertTrue(missing instanceof MissingModelHandle);
        assertTrue(missing.renderHandle().missingModel());
        assertEquals(1, registry.current().diagnostics().size());
        assertSame(missing, registry.find(MODEL_KEY).orElseThrow());
        assertEquals(0, resourceManager.getResourceStackCalls.get());
        assertEquals(1, descriptorOpens.get());
    }

    @Test
    void missingExternalTextureFailsDuringPrepareWithoutReadingTextureBytes() throws IOException {
        AtomicInteger descriptorOpens = new AtomicInteger();
        AtomicInteger meshOpens = new AtomicInteger();
        AtomicInteger textureOpens = new AtomicInteger();
        FinalResourceManager resourceManager = new FinalResourceManager(fixtureResources(
                showcaseBytes("blend_models/fixtures/static_model.json"), descriptorOpens, meshOpens, textureOpens, true, false));
        ClientModelRegistry registry = new ClientModelRegistry();
        ClientModelReloadListener listener = new ClientModelReloadListener(registry);

        PreparedModelGeneration prepared = listener.prepare(sharedState(resourceManager));

        assertTrue(prepared.loadedAssets().isEmpty());
        assertEquals(BlendDiagnosticCodes.DESC_002, prepared.primaryDiagnostics().get(MODEL_KEY).code());
        assertEquals("/materials/StaticSurface/base_color", prepared.primaryDiagnostics().get(MODEL_KEY).location());
        assertEquals(1, descriptorOpens.get());
        assertEquals(1, meshOpens.get());
        assertEquals(0, textureOpens.get());
    }

    @Test
    void applyConvertsUnsupportedAdditiveMaterialIntoOneMissingHandleWithTheConcreteReason() throws IOException {
        String descriptor = new String(showcaseBytes("blend_models/fixtures/static_model.json"), StandardCharsets.UTF_8)
                .replace("\"mode\": \"opaque\"", "\"mode\": \"additive\"");
        FinalResourceManager resourceManager = new FinalResourceManager(fixtureResources(
                descriptor.getBytes(StandardCharsets.UTF_8), new AtomicInteger(), new AtomicInteger(), new AtomicInteger(), true, true));
        ClientModelRegistry registry = new ClientModelRegistry();
        ClientModelReloadListener listener = new ClientModelReloadListener(registry);

        PreparedModelGeneration prepared = listener.prepare(sharedState(resourceManager));
        assertEquals(1, prepared.loadedAssets().size());
        assertTrue(prepared.primaryDiagnostics().isEmpty());

        listener.apply(prepared, sharedState(resourceManager));

        ModelHandle missing = registry.find(MODEL_KEY).orElseThrow();
        assertTrue(missing instanceof MissingModelHandle);
        assertTrue(missing.renderHandle().missingModel());
        assertEquals(1, registry.current().diagnostics().size());
        assertEquals(BlendDiagnosticCodes.MAT_004, registry.current().primaryDiagnostic(MODEL_KEY).orElseThrow().code());
        assertEquals("/materials/StaticSurface/mode", registry.current().primaryDiagnostic(MODEL_KEY).orElseThrow().location());
        assertTrue(registry.current().primaryDiagnostic(MODEL_KEY).orElseThrow().message()
                .contains("ADDITIVE_UNSUPPORTED_IN_P4"));
    }

    @Test
    void applyRetiresAnimationControllersAndPosesUsingTheFinallyPublishedGeneration() {
        ClientModelRegistry registry = new ClientModelRegistry();
        ClientAnimationInstanceRegistry animationRegistry = new ClientAnimationInstanceRegistry(8);
        ClientModelReloadListener listener = new ClientModelReloadListener(
                registry, animationRegistry::retireOtherGenerations);
        PreparableReloadListener.SharedState emptyResources = sharedState(new FinalResourceManager(Map.of()));
        BlendInstanceKey oldKey = BlendInstanceKey.ephemeral("reload-session", "old-generation");
        PoseCacheKey oldPose = poseKey(oldKey, 0L);
        animationRegistry.bind(oldKey, MODEL_KEY, 0L, RETIREMENT_DEFINITION);
        animationRegistry.cachePose(oldPose, new LocalPose(Map.of()));

        listener.apply(emptyPreparedGeneration(1L), emptyResources);

        assertEquals(1L, registry.current().generationId());
        assertTrue(animationRegistry.find(oldKey).isEmpty());
        assertTrue(animationRegistry.cachedPose(oldPose).isEmpty());
        assertEquals(0, animationRegistry.poseCacheMetrics().size());

        BlendInstanceKey activeKey = BlendInstanceKey.ephemeral("reload-session", "active-generation");
        PoseCacheKey activePose = poseKey(activeKey, 2L);
        listener.apply(emptyPreparedGeneration(2L), emptyResources);
        animationRegistry.bind(activeKey, MODEL_KEY, 2L, RETIREMENT_DEFINITION);
        animationRegistry.cachePose(activePose, new LocalPose(Map.of()));

        listener.apply(emptyPreparedGeneration(1L), emptyResources);
        listener.apply(emptyPreparedGeneration(1L), emptyResources);

        assertEquals(2L, registry.current().generationId());
        assertTrue(animationRegistry.find(activeKey).isPresent());
        assertTrue(animationRegistry.cachedPose(activePose).isPresent());
        assertEquals(1, animationRegistry.poseCacheMetrics().size());
    }

    @Test
    void applyReportsPublishedStructuredSummaryAndDevelopmentDetailsAfterPublication() {
        ClientModelRegistry registry = new ClientModelRegistry();
        List<String> eventOrder = new ArrayList<>();
        RecordingReloadDiagnosticSink sink = new RecordingReloadDiagnosticSink(registry, true, eventOrder);
        ClientModelReloadListener listener = new ClientModelReloadListener(
                registry,
                generationId -> {
                    assertEquals(generationId, registry.current().generationId());
                    eventOrder.add("callback:" + generationId);
                },
                new ReloadDiagnosticsReporter(sink));
        BlendDiagnostic primaryDiagnostic = BlendDiagnostic.error(
                        "BLENDLIB-TEST-PRIMARY",
                        MODEL_KEY.resourceId(),
                        MODEL_KEY.descriptorResourceId(),
                        "/mesh/0",
                        "primary fixture failure")
                .withCause(new IllegalStateException("bounded cause"));
        BlendDiagnostic globalDiagnostic = BlendDiagnostic.error(
                "BLENDLIB-TEST-GLOBAL",
                null,
                BlendResourceId.parse("blendlib_showcase:blend_models/global.json"),
                "/",
                "global fixture failure");
        PreparableReloadListener.SharedState emptyResources = sharedState(new FinalResourceManager(Map.of()));

        listener.apply(diagnosticPreparedGeneration(1L, primaryDiagnostic, List.of(globalDiagnostic)), emptyResources);

        assertEquals(1, sink.summaries.size());
        ReloadDiagnosticsReporter.Summary summary = sink.summaries.getFirst();
        assertEquals(1L, summary.candidateGeneration());
        assertEquals(1L, summary.activeGeneration());
        assertTrue(summary.published());
        assertFalse(summary.stale());
        assertEquals(1, summary.modelCount());
        assertEquals(1, summary.missingCount());
        assertEquals(2, summary.diagnosticCount());
        assertEquals(
                "blendlib_reload candidate_generation=1 active_generation=1 published=true stale=false models=1 missing=1 diagnostics=2",
                summary.structuredMessage());

        assertEquals(2, sink.details.size());
        ReloadDiagnosticsReporter.Detail primaryDetail = sink.details.getFirst();
        assertTrue(primaryDetail.primary());
        assertEquals(1L, primaryDetail.generation());
        assertEquals(DiagnosticSeverity.ERROR, primaryDetail.severity());
        assertEquals("BLENDLIB-TEST-PRIMARY", primaryDetail.code());
        assertEquals(MODEL_KEY.resourceId(), primaryDetail.modelKey());
        assertEquals(MODEL_KEY.descriptorResourceId(), primaryDetail.resourceId());
        assertEquals("/mesh/0", primaryDetail.location());
        assertEquals("primary fixture failure", primaryDetail.message());
        assertEquals("IllegalStateException: bounded cause", primaryDetail.causeSummary());
        assertEquals(
                "blendlib_reload_diagnostic generation=1 primary=true severity=ERROR code=\"BLENDLIB-TEST-PRIMARY\" "
                        + "model_key=\"blendlib_showcase:fixtures/static_model\" "
                        + "resource_id=\"blendlib_showcase:blend_models/fixtures/static_model.json\" "
                        + "location=\"/mesh/0\" message=\"primary fixture failure\" "
                        + "cause_summary=\"IllegalStateException: bounded cause\"",
                primaryDetail.structuredMessage());
        assertFalse(sink.details.get(1).primary());
        assertEquals("BLENDLIB-TEST-GLOBAL", sink.details.get(1).code());
        assertEquals(List.of("summary:1", "detail:1:true", "detail:1:false", "callback:1"), eventOrder);
    }

    @Test
    void staleOrRepeatedApplyCannotRepeatActivePrimaryDetailAndNewGenerationCanEmitAgain() {
        ClientModelRegistry registry = new ClientModelRegistry();
        RecordingReloadDiagnosticSink sink = new RecordingReloadDiagnosticSink(registry, true, new ArrayList<>());
        ClientModelReloadListener listener = new ClientModelReloadListener(
                registry, ignored -> { }, new ReloadDiagnosticsReporter(sink));
        BlendDiagnostic primaryDiagnostic = BlendDiagnostic.error(
                "BLENDLIB-TEST-STALE", MODEL_KEY.resourceId(), MODEL_KEY.descriptorResourceId(), "/mesh", "stale fixture");
        PreparableReloadListener.SharedState emptyResources = sharedState(new FinalResourceManager(Map.of()));

        listener.apply(diagnosticPreparedGeneration(1L, primaryDiagnostic, List.of()), emptyResources);
        listener.apply(diagnosticPreparedGeneration(2L, primaryDiagnostic, List.of()), emptyResources);
        listener.apply(diagnosticPreparedGeneration(1L, primaryDiagnostic, List.of()), emptyResources);
        listener.apply(diagnosticPreparedGeneration(1L, primaryDiagnostic, List.of()), emptyResources);
        listener.apply(diagnosticPreparedGeneration(3L, primaryDiagnostic, List.of()), emptyResources);

        assertEquals(5, sink.summaries.size());
        assertEquals(List.of(1L, 2L, 3L), sink.details.stream().map(ReloadDiagnosticsReporter.Detail::generation).toList());
        assertTrue(sink.details.stream().allMatch(ReloadDiagnosticsReporter.Detail::primary));

        ReloadDiagnosticsReporter.Summary firstStaleSummary = sink.summaries.get(2);
        ReloadDiagnosticsReporter.Summary repeatedStaleSummary = sink.summaries.get(3);
        assertEquals(1L, firstStaleSummary.candidateGeneration());
        assertEquals(2L, firstStaleSummary.activeGeneration());
        assertFalse(firstStaleSummary.published());
        assertTrue(firstStaleSummary.stale());
        assertEquals(firstStaleSummary, repeatedStaleSummary);
        assertEquals(3L, registry.current().generationId());
    }

    @Test
    void developmentDetailsCanBeDisabledWithoutSuppressingTheRequiredProductionSummary() {
        ClientModelRegistry registry = new ClientModelRegistry();
        RecordingReloadDiagnosticSink sink = new RecordingReloadDiagnosticSink(registry, false, new ArrayList<>());
        ClientModelReloadListener listener = new ClientModelReloadListener(
                registry, ignored -> { }, new ReloadDiagnosticsReporter(sink));
        BlendDiagnostic primaryDiagnostic = BlendDiagnostic.error(
                "BLENDLIB-TEST-NO-DEBUG", MODEL_KEY.resourceId(), MODEL_KEY.descriptorResourceId(), "/mesh", "debug disabled");

        listener.apply(
                diagnosticPreparedGeneration(1L, primaryDiagnostic, List.of()),
                sharedState(new FinalResourceManager(Map.of())));

        assertEquals(1, sink.summaries.size());
        assertEquals(0, sink.details.size());
    }

    @Test
    void missingModelDiagnosticDeduplicatorUsesGenerationAndSemanticModelKey() {
        MissingModelDiagnosticDeduplicator deduplicator = new MissingModelDiagnosticDeduplicator();
        BlendDiagnostic diagnostic = BlendDiagnostic.error(
                "BLENDLIB-TEST-DEDUP", MODEL_KEY.resourceId(), MODEL_KEY.descriptorResourceId(), "/", "dedup fixture");
        BlendModelKey otherModelKey = BlendModelKey.parse("blendlib_showcase:fixtures/other_model");

        assertTrue(deduplicator.firstForGeneration(2L, MODEL_KEY, diagnostic).isPresent());
        assertTrue(deduplicator.firstForGeneration(2L, MODEL_KEY, diagnostic).isEmpty());
        assertTrue(deduplicator.firstForGeneration(2L, otherModelKey, diagnostic).isPresent());
        assertEquals(2, deduplicator.reportedKeyCount());
        assertTrue(deduplicator.firstForGeneration(3L, MODEL_KEY, diagnostic).isPresent());
        assertEquals(1, deduplicator.reportedKeyCount());
        assertTrue(deduplicator.firstForGeneration(2L, otherModelKey, diagnostic).isEmpty());
    }

    @Test
    void boundedStreamReadRejectsDescriptorOversizeBeforeAnUnboundedReadAllBytesAllocation() {
        assertTrue(ClientModelReloadListener.MAX_DESCRIPTOR_BYTES < BlendAssetLimits.DEFAULT.maxGlbBytes());
        byte[] oversizedDescriptor = new byte[ClientModelReloadListener.MAX_DESCRIPTOR_BYTES + 1];

        BlendAssetLoadException exception = assertThrows(
                BlendAssetLoadException.class,
                () -> ClientModelReloadListener.readBoundedBytes(
                        resource(oversizedDescriptor, new AtomicInteger()),
                        MODEL_KEY.resourceId(),
                        MODEL_KEY.descriptorResourceId(),
                        ClientModelReloadListener.MAX_DESCRIPTOR_BYTES,
                        "",
                        "Descriptor"));

        assertEquals(BlendDiagnosticCodes.LIMIT_001, exception.diagnostic().code());
        assertEquals(MODEL_KEY.resourceId(), exception.diagnostic().modelKey());
        assertEquals(MODEL_KEY.descriptorResourceId(), exception.diagnostic().resourceId());
    }

    private static PreparableReloadListener.SharedState sharedState(ResourceManager resourceManager) {
        return new PreparableReloadListener.SharedState(resourceManager);
    }

    private static PreparedModelGeneration emptyPreparedGeneration(long generationId) {
        return new PreparedModelGeneration(generationId, Map.of(), Map.of(), List.of());
    }

    private static PreparedModelGeneration diagnosticPreparedGeneration(
            long generationId, BlendDiagnostic primaryDiagnostic, List<BlendDiagnostic> globalDiagnostics) {
        return new PreparedModelGeneration(
                generationId, Map.of(), Map.of(MODEL_KEY, primaryDiagnostic), globalDiagnostics);
    }

    private static PoseCacheKey poseKey(BlendInstanceKey key, long generationId) {
        return new PoseCacheKey(key, MODEL_KEY, generationId, RETIREMENT_ANIMATION_KEY, 0L);
    }

    private static AnimationControllerDefinition retirementDefinition() {
        AnimationClip clip = new AnimationClip("reload_retirement", List.of(new AnimationChannel(
                0,
                AnimationPath.TRANSLATION,
                Interpolation.LINEAR,
                new float[] {0.0F, 1.0F},
                new float[] {0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F})));
        AnimationState state = new AnimationState(
                RETIREMENT_ANIMATION_KEY, clip, true, 1.0D, 0.0D, null, List.of());
        return new AnimationControllerDefinition(RETIREMENT_ANIMATION_KEY, Map.of(RETIREMENT_ANIMATION_KEY, state));
    }

    private static Map<Identifier, Resource> fixtureResources(
            byte[] descriptorBytes,
            AtomicInteger descriptorOpens,
            AtomicInteger meshOpens,
            AtomicInteger textureOpens,
            boolean includeMesh,
            boolean includeTexture) throws IOException {
        Map<Identifier, Resource> resources = new LinkedHashMap<>();
        resources.put(DESCRIPTOR_ID, resource(descriptorBytes, descriptorOpens));
        if (includeMesh) {
            resources.put(MESH_ID, resource(showcaseBytes("models3d/fixtures/static_model.glb"), meshOpens));
        }
        if (includeTexture) {
            resources.put(TEXTURE_ID, resource(showcaseBytes("textures/blendlib/fixtures_static_model__staticsurface.png"), textureOpens));
        }
        return resources;
    }

    private static Resource resource(byte[] bytes, AtomicInteger opens) {
        byte[] copy = bytes.clone();
        return new Resource(null, () -> {
            opens.incrementAndGet();
            return new ByteArrayInputStream(copy);
        });
    }

    private static byte[] showcaseBytes(String relativePath) throws IOException {
        Path repository = repositoryRoot();
        return Files.readAllBytes(repository.resolve("blendlib-showcase", "src", "main", "resources", "assets", "blendlib_showcase")
                .resolve(relativePath));
    }

    private static byte[] p4ResourcePackBytes(String pack, String relativePath) throws IOException {
        return Files.readAllBytes(repositoryRoot()
                .resolve("test-assets", "p4-resource-packs", pack)
                .resolve(relativePath));
    }

    private static Path repositoryRoot() {
        return Path.of(System.getProperty("blendlib.projectDir")).getParent();
    }

    private static final class RecordingReloadDiagnosticSink implements ReloadDiagnosticsReporter.Sink {
        private final ClientModelRegistry registry;
        private final boolean developmentDetailsEnabled;
        private final List<String> eventOrder;
        private final List<ReloadDiagnosticsReporter.Summary> summaries = new ArrayList<>();
        private final List<ReloadDiagnosticsReporter.Detail> details = new ArrayList<>();

        private RecordingReloadDiagnosticSink(
                ClientModelRegistry registry, boolean developmentDetailsEnabled, List<String> eventOrder) {
            this.registry = registry;
            this.developmentDetailsEnabled = developmentDetailsEnabled;
            this.eventOrder = eventOrder;
        }

        @Override
        public void reportSummary(ReloadDiagnosticsReporter.Summary summary) {
            assertEquals(summary.activeGeneration(), registry.current().generationId());
            summaries.add(summary);
            eventOrder.add("summary:" + summary.activeGeneration());
        }

        @Override
        public boolean developmentDetailsEnabled() {
            return developmentDetailsEnabled;
        }

        @Override
        public void reportDevelopmentDetail(ReloadDiagnosticsReporter.Detail detail) {
            details.add(detail);
            eventOrder.add("detail:" + detail.generation() + ":" + detail.primary());
        }
    }

    private static final class FinalResourceManager implements ResourceManager {
        private final Map<Identifier, Resource> finalResources;
        private final Map<Identifier, Resource> lowerPriorityResources;
        private final AtomicInteger listResourcesCalls = new AtomicInteger();
        private final AtomicInteger getResourceStackCalls = new AtomicInteger();
        private final Map<Identifier, AtomicInteger> getResourceCalls = new LinkedHashMap<>();

        private FinalResourceManager(Map<Identifier, Resource> finalResources) {
            this(finalResources, Map.of());
        }

        /**
         * Represents Minecraft's already-resolved final resources. Lower-priority pack resources remain deliberately
         * inaccessible: a reload listener must not inspect resource stacks or fall back after final selection.
         */
        private FinalResourceManager(
                Map<Identifier, Resource> finalResources, Map<Identifier, Resource> lowerPriorityResources) {
            this.finalResources = Map.copyOf(finalResources);
            this.lowerPriorityResources = Map.copyOf(lowerPriorityResources);
        }

        @Override
        public Set<String> getNamespaces() {
            return finalResources.keySet().stream().map(Identifier::getNamespace).collect(java.util.stream.Collectors.toUnmodifiableSet());
        }

        @Override
        public Optional<Resource> getResource(Identifier identifier) {
            getResourceCalls.computeIfAbsent(identifier, ignored -> new AtomicInteger()).incrementAndGet();
            return Optional.ofNullable(finalResources.get(identifier));
        }

        private int getResourceCalls(Identifier identifier) {
            AtomicInteger calls = getResourceCalls.get(identifier);
            return calls == null ? 0 : calls.get();
        }

        @Override
        public List<Resource> getResourceStack(Identifier identifier) {
            getResourceStackCalls.incrementAndGet();
            throw new AssertionError("Reload must use ResourceManager final-resource methods, not resource stacks");
        }

        @Override
        public Map<Identifier, Resource> listResources(String path, Predicate<Identifier> filter) {
            listResourcesCalls.incrementAndGet();
            Map<Identifier, Resource> selected = new LinkedHashMap<>();
            finalResources.forEach((identifier, resource) -> {
                if (identifier.getPath().startsWith(path + "/") && filter.test(identifier)) {
                    selected.put(identifier, resource);
                }
            });
            return selected;
        }

        @Override
        public Map<Identifier, List<Resource>> listResourceStacks(String path, Predicate<Identifier> filter) {
            throw new AssertionError("Reload must use ResourceManager final-resource methods, not resource stacks");
        }

        @Override
        public Stream<PackResources> listPacks() {
            return Stream.empty();
        }
    }
}
