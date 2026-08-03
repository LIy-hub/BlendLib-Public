package com.liy.blendlib.fabric.client.blockentity;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.fabric.client.api.ClientModelLookup;
import com.liy.blendlib.fabric.client.api.ClientModelView;
import com.liy.blendlib.fabric.client.api.ClientRegistryView;
import com.liy.blendlib.fabric.client.render.CullingMetadata;
import com.liy.blendlib.fabric.client.render.MissingModelRenderHandle;
import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import com.liy.blendlib.fabric.client.render.RenderVisibility;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.junit.jupiter.api.Test;

class BlendBlockEntityAdapterContractsTest {
    private static final BlendModelKey KEY = BlendModelKey.parse("blockentity_test:skinned");
    private static final BlendResourceId DIMENSION = BlendResourceId.parse("minecraft:overworld");

    @Test
    void requestCarriesTypedDimensionPlusPackedPositionIdentity() {
        BlendInstanceKey.BlockEntity key = new BlendInstanceKey.BlockEntity(DIMENSION, 0x1234_5678_9ABCL);
        BlendBlockEntitySnapshotRequest request = new BlendBlockEntitySnapshotRequest(
                KEY, key, 0.25F, 0x00F000F0, 72L, 72.25D, true, 49.0D);

        assertEquals(DIMENSION, request.instanceKey().dimension());
        assertEquals(0x1234_5678_9ABCL, request.instanceKey().packedBlockPos());
        assertEquals(72L, request.clientGameTick());
        assertTrue(request.animationVisible());
        assertThrows(IllegalArgumentException.class,
                () -> new BlendBlockEntitySnapshotRequest(KEY, key, Float.NaN, 0, 0L, 0.0D, true, 0.0D));
        assertThrows(IllegalArgumentException.class,
                () -> new BlendBlockEntitySnapshotRequest(KEY, key, 0.0F, 0, -1L, 0.0D, true, 0.0D));
        BlendBlockEntitySnapshotRequest noCamera = new BlendBlockEntitySnapshotRequest(
                KEY, key, 0.0F, 0, 0L, 0.0D, true, Double.NaN);
        assertFalse(noCamera.animationVisible());
        assertEquals(Double.POSITIVE_INFINITY, noCamera.distanceToCameraSq());
    }

    @Test
    void staticRestPoseBindsTheHandleDuringExtractionAndRenderStateSourceRetainsOnlySnapshot() throws IOException {
        AtomicInteger lookups = new AtomicInteger();
        MissingModelRenderHandle handle = new MissingModelRenderHandle(KEY, 8L);
        ClientModelView view = new ClientModelView(KEY, 8L, true, handle, Optional.empty());
        ClientModelLookup lookup = lookupReturning(view, lookups);
        BlendBlockEntitySnapshotRequest request = new BlendBlockEntitySnapshotRequest(
                KEY,
                new BlendInstanceKey.BlockEntity(DIMENSION, 17L),
                0.0F,
                0x00F000F0,
                1L,
                1.0D,
                true,
                0.0D);

        ModelRenderSnapshot snapshot = StaticRestPoseBlockEntitySnapshotFactory.create(lookup, request);
        assertEquals(1, lookups.get());
        assertEquals(handle, snapshot.handle());
        assertEquals(8L, snapshot.generation());
        assertEquals(handle.bounds(), snapshot.culling().worldBounds());
        assertTrue(snapshot.culling().cullable());
        assertEquals(OverlayTexture.NO_OVERLAY, snapshot.packedOverlay());

        String renderState = readSource("BlendBlockEntityRenderState.java");
        assertTrue(renderState.contains("ModelRenderSnapshot"));
        assertFalse(renderState.contains("net.minecraft.world.level."));
        assertFalse(renderState.contains("getLevel("));
        assertDoesNotThrow(() -> new ModelRenderSnapshot(
                handle,
                Transform.IDENTITY,
                0,
                0,
                0xFFFFFFFF,
                RenderVisibility.VISIBLE,
                new CullingMetadata(handle.bounds(), true)));
    }

    @Test
    void publicBuilderAndSubmitBoundaryRemainBlockLocalAndSnapshotOnly() throws IOException {
        String packageSource = readBlockEntitySources();
        assertTrue(packageSource.contains("BlendBlockEntityRendererBuilder"));
        assertTrue(packageSource.contains("staticRestPose"));
        assertTrue(packageSource.contains("syncedSkinnedAnimation"));
        assertTrue(packageSource.contains("BlockEntityRendererRegistry.register"));
        assertTrue(packageSource.contains("new BlendInstanceKey.BlockEntity(dimension, blockPos.asLong())"));

        String renderState = readSource("BlendBlockEntityRenderState.java");
        assertTrue(renderState.contains("ModelRenderSnapshot"));
        assertFalse(renderState.contains("net.minecraft.world.level."));
        assertFalse(renderState.contains("getLevel("));

        String renderer = readSource("BlendBlockEntityRenderer.java");
        int submitStart = renderer.indexOf("public void submit(");
        assertTrue(submitStart >= 0);
        int submitEnd = renderer.indexOf("\n    }\n}", submitStart);
        assertTrue(submitEnd > submitStart);
        String submitBody = renderer.substring(submitStart, submitEnd);
        for (String forbidden : List.of(
                "getLevel(",
                "getBlockPos(",
                "BlendLibClientAnimationSync",
                "ClientAnimationSyncRuntime",
                "ModelAssetLoader",
                "GlbReader",
                "StrictJsonParser",
                "ResourceManager",
                "java.nio.file.",
                "java.io.",
                "network",
                "payload")) {
            assertFalse(submitBody.contains(forbidden), forbidden);
        }

        String consumerFixture = readTestSource("PublicBlockEntityConsumerCompileFixture.java");
        for (String forbidden : List.of(
                "com.liy.blendlib.core.",
                "com.liy.blendlib.fabric.client.reload.",
                "ModelRenderSnapshot")) {
            assertFalse(consumerFixture.contains(forbidden), forbidden);
        }
        assertTrue(consumerFixture.contains(".staticRestPose()"));
        assertTrue(consumerFixture.contains(".syncedSkinnedAnimation(fallbackAnimation)"));
    }

    @Test
    void synchronizedSkinnedFactoryUsesOnlyTheSemanticStoreDuringExtraction() throws IOException {
        String source = readSource("SyncedSkinnedBlockEntitySnapshotFactory.java");
        assertTrue(source.contains("BlendLibClientAnimationSync.runtime().blockEntityState("));
        assertTrue(source.contains("checkedRequest.instanceKey()"));
        assertTrue(source.contains("new SkinnedAnimationRuntimeInput("));
        assertTrue(source.contains("AnimationUpdateBuckets.select"));
        assertTrue(source.contains("OverlayTexture.NO_OVERLAY"));
        for (String forbidden : List.of(
                "ClientModelRegistry",
                "ModelAssetLoader",
                "AssetResolver",
                "GlbReader",
                "StrictJsonParser",
                "ResourceManager",
                "ClientPlayConnectionEvents",
                "ClientBlockEntityEvents",
                "ClientAnimationLifecycleBridge",
                "UnknownTargetQueue",
                "java.nio.file.",
                "java.io.",
                "renderer.submit(")) {
            assertFalse(source.contains(forbidden), forbidden);
        }
    }

    private static String readBlockEntitySources() throws IOException {
        Path root = Path.of(
                System.getProperty("blendlib.projectDir"),
                "src", "client", "java", "com", "liy", "blendlib", "fabric", "client", "blockentity");
        try (Stream<Path> paths = Files.walk(root)) {
            StringBuilder combined = new StringBuilder();
            for (Path path : paths.filter(candidate -> candidate.toString().endsWith(".java")).toList()) {
                combined.append(Files.readString(path));
            }
            return combined.toString();
        }
    }

    private static String readSource(String filename) throws IOException {
        return Files.readString(Path.of(
                System.getProperty("blendlib.projectDir"),
                "src", "client", "java", "com", "liy", "blendlib", "fabric", "client", "blockentity", filename));
    }

    private static String readTestSource(String filename) throws IOException {
        return Files.readString(Path.of(
                System.getProperty("blendlib.projectDir"),
                "src", "test", "java", "com", "liy", "blendlib", "fabric", "client", "blockentity", filename));
    }

    private static ClientModelLookup lookupReturning(ClientModelView view, AtomicInteger lookups) {
        return new ClientModelLookup() {
            @Override
            public ClientRegistryView snapshot() {
                return new ClientRegistryView(view.generationId(), Map.of(view.key(), view), List.of());
            }

            @Override
            public ClientModelView resolve(BlendModelKey modelKey) {
                lookups.incrementAndGet();
                return view;
            }
        };
    }
}
