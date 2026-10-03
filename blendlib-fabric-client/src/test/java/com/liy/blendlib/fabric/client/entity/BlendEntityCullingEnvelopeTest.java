package com.liy.blendlib.fabric.client.entity;

import static org.junit.jupiter.api.Assertions.*;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.core.model.Bounds;
import com.liy.blendlib.core.model.ModelAsset;
import com.liy.blendlib.core.model.ModelProfile;
import com.liy.blendlib.core.model.SocketTable;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import com.liy.blendlib.fabric.client.api.BlendLibClientServices;
import com.liy.blendlib.fabric.client.api.BlendRenderer;
import com.liy.blendlib.fabric.client.reload.ClientModelRegistry;
import com.liy.blendlib.fabric.client.reload.LoadedModelHandle;
import com.liy.blendlib.fabric.client.reload.ModelRegistryGeneration;
import com.liy.blendlib.fabric.client.render.ModelRenderHandle;
import com.liy.blendlib.fabric.client.render.PreparedRenderPrimitive;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.parallel.Isolated;

/** Headless coverage of the actual vanilla renderer entry, not just the bounds helper. */
@Isolated("Temporarily installs a CPU-only client service facade and restores the previous facade")
class BlendEntityCullingEnvelopeTest {
    private static final BlendModelKey MODEL = BlendModelKey.parse("culling_test:assembly");
    private static final Bounds SMALL_ROOT = bounds(-1, -1, -1, 1, 1, 1);
    private static final BlendEntityCullingEnvelope SMALL =
            new BlendEntityCullingEnvelope(-3, -4, 0, 3, 4, 0); // radius 5, even for yaw-only renderers
    private static final BlendEntityCullingEnvelope LARGE =
            new BlendEntityCullingEnvelope(-6, -8, 0, 6, 8, 0); // radius 10

    @BeforeAll
    static void bootstrapVanillaRegistriesWithoutCreatingAClientOrWorld() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void rejectsEveryNonFiniteEndpointReversedAxisAndUnrepresentableRadius() {
        for (int endpoint = 0; endpoint < 6; endpoint++) {
            for (double invalid : new double[] {Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
                double[] coordinates = {-1, -2, -3, 1, 2, 3};
                coordinates[endpoint] = invalid;
                assertThrows(IllegalArgumentException.class, () -> envelope(coordinates));
            }
        }
        assertThrows(IllegalArgumentException.class, () -> new BlendEntityCullingEnvelope(2, 0, 0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new BlendEntityCullingEnvelope(0, 2, 0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new BlendEntityCullingEnvelope(0, 0, 2, 1, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new BlendEntityCullingEnvelope(0, 0, 0, Double.MAX_VALUE, Double.MAX_VALUE, 0));
        assertDoesNotThrow(() -> new BlendEntityCullingEnvelope(0, 0, 0, Double.MAX_VALUE, 0, 0));
        assertDoesNotThrow(() -> new BlendEntityCullingEnvelope(2, 3, 4, 2, 3, 4));
        assertDoesNotThrow(() -> new BlendEntityCullingEnvelope(0, 0, 0, 0, 0, 0));
        assertThrows(NullPointerException.class, () -> builder().cullingEnvelope(null));
    }

    @Test
    void builderCapturesImmutableEnvelopeForEachRendererAndTranslatesEachEntityIndependently() throws Exception {
        withServices(registry -> {
            registry.publish(generation(1, SMALL_ROOT));
            BlendEntityRendererBuilder<Entity> builder = builder().staticRestPose();
            BlendEntityRenderer<Entity> originalDefault = builder.build();
            assertSame(builder, builder.cullingEnvelope(SMALL));
            BlendEntityRenderer<Entity> first = builder.build();
            builder.cullingEnvelope(LARGE);
            BlendEntityRenderer<Entity> second = builder.build();
            Entity left = entity(10, 20, 30, new AABB(9.5, 20, 29.5, 10.5, 21, 30.5));
            Entity right = entity(-40, 7, 80, new AABB(-40.5, 7, 79.5, -39.5, 8, 80.5));
            assertBox(new AABB(5, 15, 25, 15, 25, 35), culling(first, left));
            assertBox(new AABB(0, 10, 20, 20, 30, 40), culling(second, left));
            assertBox(new AABB(-45, 2, 75, -35, 12, 85), culling(first, right));
            assertBox(new AABB(-50, -3, 70, -30, 17, 90), culling(second, right));
            assertBox(new AABB(5, 15, 25, 15, 25, 35), culling(first, left));
            assertBox(new AABB(9, 19, 29, 11, 21, 31), culling(originalDefault, left));
            assertTrue(BlendEntityCullingEnvelope.class.isRecord());
            for (Field field : BlendEntityCullingEnvelope.class.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    assertTrue(java.lang.reflect.Modifier.isFinal(field.getModifiers()), field.getName());
                }
            }
        });
    }

    @Test
    void entryUnionsVanillaAndCurrentReloadGenerationWithoutCachingTheOldRootBounds() throws Exception {
        withServices(registry -> {
            var firstGeneration = generation(1, bounds(-20, -1, -1, 1, 1, 1));
            registry.publish(firstGeneration);
            BlendEntityRenderer<Entity> renderer = builder().staticRestPose().cullingEnvelope(SMALL).build();
            Entity entity = entity(10, 20, 30, new AABB(9, 19, 29, 11, 60, 31));
            assertBox(new AABB(-10, 15, 25, 15, 60, 35), culling(renderer, entity));
            registry.publish(generation(2, bounds(-1, -2, -3, 40, 2, 3)));
            assertTrue(firstGeneration.isRetired());
            assertBox(new AABB(5, 15, 25, 50, 60, 35), culling(renderer, entity));
            assertEquals(2, BlendLibClientServices.models().resolve(MODEL).generationId());
        });
    }

    @Test
    void animatedAndCustomSnapshotCallbacksAreNeverInvokedDuringCulling() throws Exception {
        withServices(registry -> {
            registry.publish(generation(1, SMALL_ROOT));
            var animated = builder()
                    .skinnedAnimation((entity, request) -> { throw new AssertionError("animation selector in culling"); })
                    .rootRotation((entity, request) -> { throw new AssertionError("root rotation in culling"); })
                    .poseModifier((entity, context, pose) -> { throw new AssertionError("pose sampling in culling"); })
                    .attachments((entity, request, sockets) -> { throw new AssertionError("attachment traversal in culling"); })
                    .cullingEnvelope(SMALL).build();
            var custom = builder().snapshotFactory((entity, request) -> {
                throw new AssertionError("snapshot extraction in culling");
            }).cullingEnvelope(SMALL).build();
            Entity entity = entity(10, 20, 30, new AABB(9.5, 20, 29.5, 10.5, 21, 30.5));
            assertBox(new AABB(5, 15, 25, 15, 25, 35), culling(animated, entity));
            assertBox(culling(animated, entity), culling(custom, entity));
            // No animation runtime is installed. A hidden animation/extraction call must fail this test.
            assertThrows(IllegalStateException.class, BlendLibClientServices::skinnedAnimationRuntime);
        });
    }

    @Test
    void omittedEnvelopePreservesBothExistingYawAndRootRotationBounds() throws Exception {
        withServices(registry -> {
            Bounds asymmetric = bounds(-2, -3, -4, 5, 6, 7);
            registry.publish(generation(1, asymmetric));
            Entity entity = entity(10, 20, 30, new AABB(-5, 19, 29, 11, 21, 31));
            var ordinary = builder().staticRestPose().build();
            assertBox(new AABB(-5, 17, 26, 15, 26, 37), culling(ordinary, entity));
            var rotated = builder().skinnedAnimation((ignored, request) -> BlendAnimationKey.parse("culling_test:idle"))
                    .rootRotation((ignored, request) -> { throw new AssertionError("rotation selector in culling"); })
                    .build();
            double radius = Math.sqrt(110);
            assertBox(new AABB(-5, 20 - radius, 30 - radius, 10 + radius, 20 + radius, 30 + radius),
                    culling(rotated, entity));
        });
    }

    @Test
    void finiteWorldTranslationOverflowCannotPoisonOrdinaryRootUnion() throws Exception {
        withServices(registry -> {
            registry.publish(generation(1, SMALL_ROOT));
            var normal = builder().staticRestPose().build();
            var enormous = builder().staticRestPose()
                    .cullingEnvelope(new BlendEntityCullingEnvelope(-1e308, 0, 0, 1e308, 0, 0)).build();
            Entity entity = entity(1e308, 0, 0, new AABB(1, 2, 3, 4, 5, 6));
            AABB fallback = culling(enormous, entity);
            assertFinite(fallback);
            assertBox(culling(normal, entity), fallback);
        });
    }

    @Test
    void envelopeDoesNotForceRenderingOrBypassVanillaDistanceAndFrustumRejection() throws Exception {
        withServices(registry -> {
            registry.publish(generation(1, SMALL_ROOT));
            var renderer = builder().staticRestPose().cullingEnvelope(LARGE).build();
            Entity entity = entity(0, 0, 0, new AABB(-.5, 0, -.5, .5, 1, .5));
            int[] frustumCalls = {0};
            Frustum rejectsAll = new Frustum(new Matrix4f(), new Matrix4f()) {
                @Override public boolean isVisible(AABB box) {
                    frustumCalls[0]++;
                    assertTrue(box.minX <= -10 && box.maxX >= 10, "vanilla receives the expanded envelope");
                    return false;
                }
            };
            assertFalse(shouldRender(renderer, entity, rejectsAll, 1e10, 1e10, 1e10));
            assertEquals(0, frustumCalls[0], "vanilla distance rejection remains effective");
            assertFalse(shouldRender(renderer, entity, rejectsAll, 0, 0, 0));
            assertEquals(1, frustumCalls[0], "vanilla frustum rejection remains effective");
            assertTrue(Arrays.stream(BlendEntityRenderer.class.getDeclaredMethods())
                    .noneMatch(method -> method.getName().equals("shouldRender")
                            || method.getName().equals("affectedByCulling")));
        });
    }

    private static BlendEntityCullingEnvelope envelope(double[] values) {
        return new BlendEntityCullingEnvelope(values[0], values[1], values[2], values[3], values[4], values[5]);
    }

    private static BlendEntityRendererBuilder<Entity> builder() {
        return BlendEntityRenderer.builder(allocate(EntityRendererProvider.Context.class), MODEL,
                new BlendRenderer((snapshot, context) -> { throw new AssertionError("submission in culling"); }));
    }

    /** Allocate only the inert vanilla data carriers; renderer construction and culling remain real. */
    private static Entity entity(double x, double y, double z, AABB bounds) throws Exception {
        Entity entity = allocate(Marker.class);
        Field position = Entity.class.getDeclaredField("position");
        position.setAccessible(true);
        position.set(entity, new net.minecraft.world.phys.Vec3(x, y, z));
        entity.xOld = x;
        entity.yOld = y;
        entity.zOld = z;
        entity.setBoundingBox(bounds);
        return entity;
    }

    private static <T> T allocate(Class<T> type) {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field field = unsafeClass.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return type.cast(unsafeClass.getMethod("allocateInstance", Class.class).invoke(field.get(null), type));
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Could not allocate isolated vanilla fixture " + type.getName(), exception);
        }
    }

    /** 26.1.2 takes Entity; 26.3 adds partialTick. Both execute the shipped override. */
    private static AABB culling(BlendEntityRenderer<Entity> renderer, Entity entity) throws Exception {
        Method method = Arrays.stream(BlendEntityRenderer.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("getBoundingBoxForCulling"))
                .findFirst().orElseThrow();
        method.setAccessible(true);
        return (AABB) (method.getParameterCount() == 1
                ? method.invoke(renderer, entity) : method.invoke(renderer, entity, 1F));
    }

    private static boolean shouldRender(BlendEntityRenderer<Entity> renderer, Entity entity,
            Frustum frustum, double x, double y, double z) throws Exception {
        Method method = Arrays.stream(EntityRenderer.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("shouldRender") && !candidate.isBridge())
                .findFirst().orElseThrow();
        return (boolean) (method.getParameterCount() == 5
                ? method.invoke(renderer, entity, frustum, x, y, z)
                : method.invoke(renderer, entity, frustum, x, y, z, 1F));
    }

    private static void assertFinite(AABB box) {
        for (double value : new double[] {box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ}) {
            assertTrue(Double.isFinite(value));
        }
    }

    private static void assertBox(AABB expected, AABB actual) {
        assertFinite(actual);
        assertArrayEquals(new double[] {expected.minX, expected.minY, expected.minZ, expected.maxX, expected.maxY, expected.maxZ},
                new double[] {actual.minX, actual.minY, actual.minZ, actual.maxX, actual.maxY, actual.maxZ}, 1e-9);
    }

    private static Bounds bounds(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        return new Bounds(new Vec3(minX, minY, minZ), new Vec3(maxX, maxY, maxZ));
    }

    private static ModelRegistryGeneration generation(long id, Bounds bounds) {
        ModelRenderHandle handle = new ModelRenderHandle() {
            public BlendModelKey modelKey() { return MODEL; }
            public long generation() { return id; }
            public Bounds bounds() { return bounds; }
            public float unitsToBlocksScale() { return 1; }
            public List<PreparedRenderPrimitive> primitives() { throw new AssertionError("geometry traversal in culling"); }
            public Transform nodeTransform(int nodeIndex) { throw new AssertionError("node traversal in culling"); }
            public boolean missingModel() { return false; }
        };
        ModelAsset asset = new ModelAsset(MODEL.resourceId(), MODEL.descriptorResourceId(), id, ModelProfile.RIGID_V1,
                1D, Map.of(), null, List.of(), List.of(), List.of(), null, List.of(),
                new SocketTable(Map.of()), bounds, List.of());
        return new ModelRegistryGeneration(id, Map.of(MODEL, new LoadedModelHandle(MODEL, asset, handle)), Map.of(), List.of());
    }

    @SuppressWarnings("unchecked")
    private static void withServices(CheckedRegistryTest test) throws Exception {
        Field field = BlendLibClientServices.class.getDeclaredField("ACTIVE");
        field.setAccessible(true);
        AtomicReference<Object> active = (AtomicReference<Object>) field.get(null);
        Object previous = active.getAndSet(null);
        try {
            ClientModelRegistry registry = new ClientModelRegistry();
            BlendLibClientServices.initialize(registry, (snapshot, context) -> {
                throw new AssertionError("renderer submission in culling");
            });
            test.run(registry);
        } finally {
            active.set(previous);
        }
    }

    @FunctionalInterface
    private interface CheckedRegistryTest {
        void run(ClientModelRegistry registry) throws Exception;
    }
}
