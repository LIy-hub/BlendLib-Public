package com.liy.blendlib.fabric.client.entity;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.fabric.client.animation.AnimationUpdateBucket;
import com.liy.blendlib.fabric.client.animation.AnimationUpdateBuckets;
import com.liy.blendlib.fabric.client.api.ClientModelLookup;
import com.liy.blendlib.fabric.client.api.ClientModelView;
import com.liy.blendlib.fabric.client.api.ClientRegistryView;
import com.liy.blendlib.fabric.client.render.MissingModelRenderHandle;
import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import com.liy.blendlib.core.model.Bounds;
import com.liy.blendlib.core.model.Vec3;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

class EntityAdapterContractsTest {
    private static final BlendModelKey KEY = BlendModelKey.parse("entity_test:rigid");

    @Test
    void extractionRequestIsFiniteAndDoesNotNeedWorldOrResourceAccess() {
        BlendEntitySnapshotRequest request = new BlendEntitySnapshotRequest(
                KEY, 0.25F, 0, 12.0F, 1.0D, 2.0D, 3.0D, 12L, true, 64.0D);
        assertEquals(12L, request.clientGameTick());
        assertTrue(request.animationVisible());
        assertEquals(64.0D, request.distanceToCameraSq());
        assertThrows(IllegalArgumentException.class,
                () -> new BlendEntitySnapshotRequest(KEY, Float.NaN, 0, 12.0F, 1.0D, 2.0D, 3.0D, 12L, true, 64.0D));
        assertThrows(IllegalArgumentException.class,
                () -> new BlendEntitySnapshotRequest(KEY, 0.0F, 0, -1.0F, 1.0D, 2.0D, 3.0D, 12L, true, 64.0D));
        assertThrows(IllegalArgumentException.class,
                () -> new BlendEntitySnapshotRequest(KEY, 0.0F, 0, 12.0F, 1.0D, 2.0D, 3.0D, -1L, true, 64.0D));
        BlendEntitySnapshotRequest noCamera = new BlendEntitySnapshotRequest(
                KEY, 0.0F, 0, 12.0F, 1.0D, 2.0D, 3.0D, 12L, true, Double.NaN);
        assertFalse(noCamera.animationVisible());
        assertEquals(Double.POSITIVE_INFINITY, noCamera.distanceToCameraSq());
    }

    @Test
    void staticRestPoseBindsThePreparedHandleDuringExtractionRatherThanSubmit() {
        AtomicInteger lookups = new AtomicInteger();
        MissingModelRenderHandle handle = new MissingModelRenderHandle(KEY, 8L);
        ClientModelView view = new ClientModelView(KEY, 8L, true, handle, Optional.empty());
        ClientModelLookup lookup = new ClientModelLookup() {
            @Override
            public ClientRegistryView snapshot() {
                return new ClientRegistryView(8L, Map.of(KEY, view), List.of());
            }

            @Override
            public ClientModelView resolve(BlendModelKey modelKey) {
                lookups.incrementAndGet();
                return view;
            }
        };
        ModelRenderSnapshot snapshot = StaticRestPoseEntitySnapshotFactory.create(
                lookup, new BlendEntitySnapshotRequest(
                        KEY, 0.0F, 0x00F000F0, 1.0F, 0.0D, 0.0D, 0.0D, 1L, true, 0.0D));

        assertEquals(1, lookups.get());
        assertEquals(handle, snapshot.handle());
        assertEquals(8L, snapshot.generation());
        assertEquals(OverlayTexture.NO_OVERLAY, snapshot.packedOverlay());
    }

    @Test
    void colossalVisualEnvelopeKeepsNearbyVisibleGeometryAtFullAnimationCadence() {
        Bounds colossalBounds = new Bounds(
                new Vec3(-60.0F, -20.0F, -60.0F), new Vec3(60.0F, 20.0F, 20.0F));

        double cameraFiftyFiveBlocksFromOrigin =
                SkinnedAnimationEntitySnapshotFactory.distanceSquaredToVisualEnvelope(55.0D * 55.0D, colossalBounds);
        assertEquals(
                AnimationUpdateBucket.VISIBLE_NEAR,
                AnimationUpdateBuckets.select(true, cameraFiftyFiveBlocksFromOrigin));

        double cameraTwoHundredBlocksFromOrigin =
                SkinnedAnimationEntitySnapshotFactory.distanceSquaredToVisualEnvelope(200.0D * 200.0D, colossalBounds);
        assertEquals(
                AnimationUpdateBucket.VISIBLE_FAR,
                AnimationUpdateBuckets.select(true, cameraTwoHundredBlocksFromOrigin));
        assertEquals(
                Double.POSITIVE_INFINITY,
                SkinnedAnimationEntitySnapshotFactory.distanceSquaredToVisualEnvelope(Double.NaN, colossalBounds));
    }

    @Test
    void minecraftYawRotatesTheCanonicalModelForwardAxis() {
        Vec3 canonicalForward = new Vec3(0.0F, 0.0F, 1.0F);

        Vec3 south = EntityRootTransforms.fromMinecraftYawDegrees(0.0F).transformPoint(canonicalForward);
        assertEquals(0.0F, south.x(), 0.0001F);
        assertEquals(1.0F, south.z(), 0.0001F);

        Vec3 west = EntityRootTransforms.fromMinecraftYawDegrees(90.0F).transformPoint(canonicalForward);
        assertEquals(-1.0F, west.x(), 0.0001F);
        assertEquals(0.0F, west.z(), 0.0001F);

        Vec3 east = EntityRootTransforms.fromMinecraftYawDegrees(-90.0F).transformPoint(canonicalForward);
        assertEquals(1.0F, east.x(), 0.0001F);
        assertEquals(0.0F, east.z(), 0.0001F);
        assertThrows(
                IllegalArgumentException.class,
                () -> EntityRootTransforms.fromMinecraftYawDegrees(Float.NaN));
    }

    @Test
    void completeRootQuaternionCanPitchTheCanonicalForwardAxisVertically() {
        float halfSqrt = (float) Math.sqrt(0.5D);
        BlendEntityRotation pitchUp = BlendEntityRotation.normalized(
                -halfSqrt, 0.0F, 0.0F, halfSqrt);

        Vec3 up = EntityRootTransforms.fromEntityRotation(pitchUp)
                .transformPoint(new Vec3(0.0F, 0.0F, 1.0F));
        assertEquals(0.0F, up.x(), 0.0001F);
        assertEquals(1.0F, up.y(), 0.0001F);
        assertEquals(0.0F, up.z(), 0.0001F);
        assertThrows(NullPointerException.class, () -> EntityRootTransforms.fromEntityRotation(null));
    }

    @Test
    void cullingBoundsAreFiniteTranslatedAndUnionTheVanillaEntityBounds() {
        AABB entityBounds = new AABB(7.0D, 18.0D, 29.0D, 11.0D, 21.0D, 31.0D);
        Bounds preparedBounds = new Bounds(new Vec3(-2.0F, -3.0F, -4.0F), new Vec3(5.0F, 6.0F, 7.0F));

        AABB result = EntityCullingBounds.unionWithPreparedBounds(entityBounds, 10.0D, 20.0D, 30.0D, preparedBounds);
        assertEquals(7.0D, result.minX);
        assertEquals(17.0D, result.minY);
        assertEquals(26.0D, result.minZ);
        assertEquals(15.0D, result.maxX);
        assertEquals(26.0D, result.maxY);
        assertEquals(37.0D, result.maxZ);
        assertFalse(result.hasNaN());
        for (double endpoint : new double[] {
                result.minX, result.minY, result.minZ, result.maxX, result.maxY, result.maxZ}) {
            assertTrue(Double.isFinite(endpoint));
        }
    }

    @Test
    void entityFrustumBoxConsumesThePreparedAnimatedEnvelopeBeyondRestPose() {
        AABB restEntityBounds = new AABB(99.5D, 0.0D, -0.5D, 100.5D, 2.0D, 0.5D);
        Bounds allClipEnvelope = new Bounds(
                new Vec3(-12.0F, -12.0F, -12.0F), new Vec3(12.0F, 12.0F, 12.0F));

        AABB result = EntityCullingBounds.unionWithPreparedBounds(
                restEntityBounds, 100.0D, 0.0D, 0.0D, allClipEnvelope);

        assertEquals(88.0D, result.minX);
        assertEquals(112.0D, result.maxX);
        assertEquals(-12.0D, result.minY);
        assertEquals(12.0D, result.maxY);
    }

    @Test
    void arbitraryRootRotationUsesAnOriginCenteredInvariantCullingSphere() {
        AABB entityBounds = new AABB(9.5D, 19.5D, 29.5D, 10.5D, 20.5D, 30.5D);
        Bounds asymmetric = new Bounds(
                new Vec3(-2.0F, -3.0F, -4.0F),
                new Vec3(5.0F, 6.0F, 7.0F));
        double radius = Math.sqrt(5.0D * 5.0D + 6.0D * 6.0D + 7.0D * 7.0D);

        AABB result = EntityCullingBounds.unionWithPreparedBounds(
                entityBounds, 10.0D, 20.0D, 30.0D, asymmetric, true);

        assertEquals(10.0D - radius, result.minX, 0.000001D);
        assertEquals(20.0D - radius, result.minY, 0.000001D);
        assertEquals(30.0D - radius, result.minZ, 0.000001D);
        assertEquals(10.0D + radius, result.maxX, 0.000001D);
        assertEquals(20.0D + radius, result.maxY, 0.000001D);
        assertEquals(30.0D + radius, result.maxZ, 0.000001D);
    }

    @Test
    void invariantCullingContainsEveryAsymmetricCornerAcrossRandomRootRotations() {
        double entityX = 10.0D;
        double entityY = -4.0D;
        double entityZ = 31.0D;
        Bounds asymmetric = new Bounds(
                new Vec3(-2.0F, -3.0F, -4.0F),
                new Vec3(5.0F, 6.0F, 7.0F));
        AABB invariant = EntityCullingBounds.unionWithPreparedBounds(
                new AABB(entityX, entityY, entityZ, entityX, entityY, entityZ),
                entityX,
                entityY,
                entityZ,
                asymmetric,
                true);
        Random random = new Random(0xA11C011L);
        for (int sample = 0; sample < 256; sample++) {
            // Shoemake's deterministic uniform unit-quaternion construction.
            double u1 = random.nextDouble();
            double u2 = random.nextDouble();
            double u3 = random.nextDouble();
            double root = Math.sqrt(1.0D - u1);
            double complement = Math.sqrt(u1);
            BlendEntityRotation rotation = BlendEntityRotation.normalized(
                    (float) (root * Math.sin(Math.PI * 2.0D * u2)),
                    (float) (root * Math.cos(Math.PI * 2.0D * u2)),
                    (float) (complement * Math.sin(Math.PI * 2.0D * u3)),
                    (float) (complement * Math.cos(Math.PI * 2.0D * u3)));
            for (float x : new float[] {asymmetric.min().x(), asymmetric.max().x()}) {
                for (float y : new float[] {asymmetric.min().y(), asymmetric.max().y()}) {
                    for (float z : new float[] {asymmetric.min().z(), asymmetric.max().z()}) {
                        Vec3 corner = EntityRootTransforms.fromEntityRotation(rotation)
                                .transformPoint(new Vec3(x, y, z));
                        assertTrue(entityX + corner.x() >= invariant.minX - 0.00001D);
                        assertTrue(entityX + corner.x() <= invariant.maxX + 0.00001D);
                        assertTrue(entityY + corner.y() >= invariant.minY - 0.00001D);
                        assertTrue(entityY + corner.y() <= invariant.maxY + 0.00001D);
                        assertTrue(entityZ + corner.z() >= invariant.minZ - 0.00001D);
                        assertTrue(entityZ + corner.z() <= invariant.maxZ + 0.00001D);
                    }
                }
            }
        }
    }

    @Test
    void cullingLookupOccursBeforeSubmitAndInvalidCoordinatesKeepTheVanillaBounds() throws IOException {
        AtomicInteger lookups = new AtomicInteger();
        MissingModelRenderHandle handle = new MissingModelRenderHandle(KEY, 9L);
        ClientModelView view = new ClientModelView(KEY, 9L, true, handle, Optional.empty());
        ClientModelLookup lookup = lookupReturning(view, lookups);
        AABB entityBounds = new AABB(1.0D, 2.0D, 3.0D, 4.0D, 5.0D, 6.0D);

        AABB result = EntityCullingBounds.unionWithCurrentModelBounds(lookup, KEY, entityBounds, 10.0D, 20.0D, 30.0D);
        assertEquals(1, lookups.get());
        assertEquals(1.0D, result.minX);
        assertEquals(2.0D, result.minY);
        assertEquals(3.0D, result.minZ);
        assertEquals(10.25D, result.maxX);
        assertEquals(20.25D, result.maxY);
        assertEquals(30.0D, result.maxZ);
        assertEquals(entityBounds, EntityCullingBounds.unionWithPreparedBounds(
                entityBounds, Double.NaN, 20.0D, 30.0D, handle.bounds()));
        AABB recovered = EntityCullingBounds.unionWithPreparedBounds(
                new AABB(Double.NaN, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D),
                10.0D,
                20.0D,
                30.0D,
                handle.bounds());
        assertFalse(recovered.hasNaN());
        assertTrue(Double.isFinite(recovered.minX));
        assertTrue(Double.isFinite(recovered.maxZ));

        String rendererSource = Files.readString(Path.of(
                System.getProperty("blendlib.projectDir"), "src", "client", "java", "com", "liy", "blendlib",
                "fabric", "client", "entity", "BlendEntityRenderer.java"));
        int submitOffset = rendererSource.indexOf("public void submit(");
        assertTrue(submitOffset >= 0);
        assertFalse(rendererSource.substring(submitOffset).contains(".resolve("));
        assertFalse(rendererSource.substring(submitOffset).contains(".level()"));
        assertTrue(rendererSource.contains("getBoundingBoxForCulling"));
    }

    @Test
    void entityAdapterUsesPreparedSnapshotAndOnlyPublicRenderingTypes() throws IOException {
        String source = readEntitySource();
        for (String forbidden : new String[] {
                "ModelAssetLoader", "AssetResolver", "GlbReader", "StrictJsonParser", "ResourceManager",
                "ClientModelRegistry", "Minecraft.getInstance", "java.nio.file.", "java.io."}) {
            assertFalse(source.contains(forbidden), forbidden);
        }
        assertFalse(source.contains("org.lwjgl.opengl"));
        assertTrue(source.contains("EntityRendererRegistry.register"));
        assertTrue(source.contains("SubmitNodeCollector"));
        assertTrue(source.contains("staticRestPose"));
        assertTrue(source.contains("skinnedAnimation"));
        assertTrue(source.contains("SkinnedAnimationEntitySnapshotFactory"));
        assertTrue(source.contains("AnimationUpdateBuckets.select"));
        assertTrue(source.contains("VisualEventDispatcher"));
        String rendererSource = Files.readString(Path.of(
                System.getProperty("blendlib.projectDir"), "src", "client", "java", "com", "liy", "blendlib",
                "fabric", "client", "entity", "BlendEntityRenderer.java"));
        int submitOffset = rendererSource.indexOf("public void submit(");
        assertTrue(submitOffset >= 0);
        assertFalse(rendererSource.substring(submitOffset).contains(".resolve("));
        assertFalse(rendererSource.substring(submitOffset).contains(".extract("));
        assertFalse(rendererSource.substring(submitOffset).contains("skinnedAnimationRuntime"));
        assertFalse(rendererSource.substring(submitOffset).contains(".level()"));
        String consumerFixture = Files.readString(Path.of(
                System.getProperty("blendlib.projectDir"), "src", "test", "java", "com", "liy", "blendlib",
                "fabric", "client", "entity", "PublicEntityConsumerCompileFixture.java"));
        for (String forbidden : new String[] {
                "com.liy.blendlib.core.", "com.liy.blendlib.fabric.client.reload.", "ModelRenderSnapshot"}) {
            assertFalse(consumerFixture.contains(forbidden), forbidden);
        }
        assertTrue(consumerFixture.contains(".staticRestPose()"));
        assertTrue(consumerFixture.contains(".synchronizedSkinnedAnimation("));
        assertTrue(consumerFixture.contains(".poseModifier("));
        assertTrue(consumerFixture.contains(".rootRotation("));
        assertTrue(consumerFixture.contains("BlendEntityRotation.normalized("));
        assertTrue(consumerFixture.contains("basePose.withRotation("));
        assertFalse(consumerFixture.contains("fabric.client.animation.sync"));
        assertTrue(rendererSource.contains("entity.level().getGameTime()"));
        assertFalse(rendererSource.contains("entity.tickCount"));
        String builderSource = Files.readString(Path.of(
                System.getProperty("blendlib.projectDir"), "src", "client", "java", "com", "liy", "blendlib",
                "fabric", "client", "entity", "BlendEntityRendererBuilder.java"));
        assertTrue(builderSource.contains("SyncedSkinnedAnimationStateSelector"));
        assertTrue(builderSource.contains("BlendLibClientAnimationSync.runtime().entityState(entity.getId())"));
        assertTrue(builderSource.contains("BlendEntityPoseModifier<? super E>"));
        assertTrue(builderSource.contains("BlendEntityRootRotationSelector<? super E>"));
        assertTrue(builderSource.contains("Configure skinnedAnimation before configuring a pose modifier"));
        assertTrue(builderSource.contains("Configure skinnedAnimation before configuring root rotation"));
        String modifierSource = Files.readString(Path.of(
                System.getProperty("blendlib.projectDir"), "src", "client", "java", "com", "liy", "blendlib",
                "fabric", "client", "entity", "BlendEntityPoseModifier.java"));
        assertTrue(modifierSource.contains("BlendEntityRotationPose basePose"));
        assertFalse(modifierSource.contains("LocalPose"));
        assertFalse(modifierSource.contains("com.liy.blendlib.core."));
    }

    @Test
    void skinnedEntityPathAdvancesOnlyDuringExtractionAndForwardsVisualEventsLocally() throws IOException {
        Path sourcePath = Path.of(
                System.getProperty("blendlib.projectDir"), "src", "client", "java", "com", "liy", "blendlib",
                "fabric", "client", "entity", "SkinnedAnimationEntitySnapshotFactory.java");
        String source = Files.readString(sourcePath);
        for (String forbidden : new String[] {
                "ClientModelRegistry", "ModelAssetLoader", "AssetResolver", "GlbReader", "StrictJsonParser",
                "ResourceManager", "ClientPlayConnectionEvents", "ClientEntityEvents", "ClientBlockEntityEvents",
                "java.nio.file.", "java.io.", "Minecraft.getInstance", "renderer.submit("}) {
            assertFalse(source.contains(forbidden), forbidden);
        }
        assertTrue(source.contains("AnimationUpdateBuckets.select"));
        assertTrue(source.contains("SkinnedAnimationRuntimeInput"));
        assertTrue(source.contains("checkedRequest.clientGameTick()"));
        assertTrue(source.contains("activeEntityKey(checkedEntity.getId())"));
        assertTrue(source.contains("if (instanceKey.isEmpty())"));
        assertTrue(source.contains("return missingSnapshot(model, checkedRequest, rootTransform);"));
        assertFalse(source.contains(".entityKey(checkedEntity.getId())"));
        assertTrue(source.contains("VisualEventDispatcher"));
        assertTrue(source.contains("onVisualEvent"));
        assertTrue(source.contains("poseModifier.modify("));
        assertTrue(source.contains("new BlendEntityPoseContext(checkedRequest, animationContext)"));
        assertTrue(source.contains("BlendEntityRotationPoseAdapter.capture(basePose)"));
        assertTrue(source.contains("BlendEntityRotationPoseAdapter.apply(basePose, capturedBase, modifiedPose)"));
        assertTrue(source.contains("RenderVisibility.CULLED"));
        assertTrue(source.contains("OverlayTexture.NO_OVERLAY"));
    }

    private static String readEntitySource() throws IOException {
        Path root = Path.of(System.getProperty("blendlib.projectDir"), "src", "client", "java", "com", "liy", "blendlib", "fabric", "client", "entity");
        try (Stream<Path> paths = Files.walk(root)) {
            StringBuilder combined = new StringBuilder();
            for (Path path : paths.filter(candidate -> candidate.toString().endsWith(".java")).toList()) {
                combined.append(Files.readString(path));
            }
            return combined.toString();
        }
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
