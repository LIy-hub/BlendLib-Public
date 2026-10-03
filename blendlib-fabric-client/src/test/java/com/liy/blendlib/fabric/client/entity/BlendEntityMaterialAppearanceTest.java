package com.liy.blendlib.fabric.client.entity;

import static org.junit.jupiter.api.Assertions.*;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.core.model.Bounds;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.fabric.client.api.BlendRenderer;
import com.liy.blendlib.fabric.client.render.*;
import com.mojang.blaze3d.vertex.PoseStack;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.Test;

class BlendEntityMaterialAppearanceTest {
    private static final BlendModelKey KEY = BlendModelKey.parse("appearance:entity");
    private static final BlendEntitySnapshotRequest REQUEST = new BlendEntitySnapshotRequest(
            KEY, .5f, 123, 1, 0, 0, 0, 2, true, 4);

    @Test
    void selectorIsEvaluatedOncePerExtractionAndNeverDuringRepeatedSubmit() {
        var calls = new AtomicInteger();
        var factories = new AtomicInteger();
        var submitted = new AtomicInteger();
        var selection = new HashMap<>(Map.of("body", new MaterialSlotAppearance(0xff0000, false)));
        var base = snapshot(false);
        BlendEntitySnapshotFactory<Entity> factory = (entity, request) -> {
            assertSame(REQUEST, request);
            factories.incrementAndGet();
            return base;
        };
        var capturedFactory = BlendEntityRendererBuilder.captureMaterialAppearance(factory, (entity, request) -> {
            assertSame(REQUEST, request);
            calls.incrementAndGet();
            return selection;
        });
        var captured = capturedFactory.create(null, REQUEST);
        assertEquals(1, calls.get());
        assertEquals(1, factories.get());
        selection.clear();
        selection.put("unknown", MaterialSlotAppearance.unchanged());
        var renderer = new BlendRenderer((snapshot, context) -> {
            assertSame(captured, snapshot);
            submitted.incrementAndGet();
        });
        var collector = (SubmitNodeCollector) Proxy.newProxyInstance(SubmitNodeCollector.class.getClassLoader(),
                new Class<?>[]{SubmitNodeCollector.class}, (proxy, method, args) -> null);
        var context = new RenderSubmissionContext(new PoseStack(), collector);
        renderer.submit(captured, context);
        renderer.submit(captured, context);
        assertEquals(2, submitted.get());
        assertEquals(1, calls.get());
        assertEquals(1, factories.get());
        assertTrue(captured.unknownMaterialSlots().isEmpty());
        assertEquals(List.of("unknown"), capturedFactory.create(null, REQUEST).unknownMaterialSlots());
        assertEquals(2, calls.get());
    }

    @Test
    void absentSnapshotsSelectorsAndDiagnosticModelsDoNotCallSelector() {
        BlendEntityMaterialAppearance<Entity> unexpected = (entity, request) -> {
            fail("Selector must not run for absent or diagnostic snapshots");
            return Map.of();
        };
        assertNull(BlendEntityRendererBuilder.captureMaterialAppearance(
                (Entity entity, BlendEntitySnapshotRequest request) -> null, unexpected).create(null, REQUEST));
        var missing = snapshot(true);
        assertSame(missing, BlendEntityRendererBuilder.captureMaterialAppearance(
                (Entity entity, BlendEntitySnapshotRequest request) -> missing, unexpected).create(null, REQUEST));
        var ordinary = snapshot(false);
        assertSame(ordinary, BlendEntityRendererBuilder.captureMaterialAppearance(
                (Entity entity, BlendEntitySnapshotRequest request) -> ordinary, null).create(null, REQUEST));
    }

    @Test
    void builderAcceptsAppearanceForEverySnapshotPathAndRejectsNull() {
        BlendEntityMaterialAppearance<Entity> selector = (entity, request) -> Map.of();
        for (var builder : List.of(builder(), builder().staticRestPose(),
                builder().snapshotFactory((entity, request) -> null),
                builder().skinnedAnimation((entity, request) -> BlendAnimationKey.parse("appearance:idle")),
                builder().synchronizedSkinnedAnimation((entity, request) -> BlendAnimationKey.parse("appearance:idle")))) {
            assertSame(builder, builder.materialAppearance(selector));
            assertThrows(NullPointerException.class, () -> builder.materialAppearance(null));
        }
    }

    @Test
    void publicCallbackAndSnapshotDescriptorsStaySmallAndPinned() throws ReflectiveOperationException {
        var select = BlendEntityMaterialAppearance.class.getDeclaredMethod("select", Entity.class, BlendEntitySnapshotRequest.class);
        assertEquals(Map.class, select.getReturnType());
        assertEquals(1, BlendEntityMaterialAppearance.class.getDeclaredMethods().length);
        assertTrue(BlendEntityMaterialAppearance.class.isAnnotationPresent(FunctionalInterface.class));
        assertEquals(BlendEntityRendererBuilder.class, BlendEntityRendererBuilder.class
                .getMethod("materialAppearance", BlendEntityMaterialAppearance.class).getReturnType());
        assertEquals(ModelRenderSnapshot.class, ModelRenderSnapshot.class
                .getMethod("withMaterialAppearance", Map.class).getReturnType());
        assertEquals(List.class, ModelRenderSnapshot.class.getMethod("unknownMaterialSlots").getReturnType());
        assertEquals(List.class, ModelRenderHandle.class.getMethod("materialSlots").getReturnType());
        assertTrue(MaterialSlotAppearance.class.isRecord());
        assertArrayEquals(new Class<?>[]{int.class, boolean.class}, Arrays.stream(
                MaterialSlotAppearance.class.getRecordComponents()).map(RecordComponent::getType).toArray(Class<?>[]::new));
    }

    private static BlendEntityRendererBuilder<Entity> builder() {
        try {
            var type = Class.forName("sun.misc.Unsafe");
            var field = type.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            var context = (EntityRendererProvider.Context) type.getMethod("allocateInstance", Class.class)
                    .invoke(field.get(null), EntityRendererProvider.Context.class);
            return new BlendEntityRendererBuilder<>(context, KEY, new BlendRenderer((snapshot, submission) -> {}));
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static ModelRenderSnapshot snapshot(boolean missing) {
        var delegate = new MissingModelRenderHandle(KEY, 1);
        ModelRenderHandle handle = missing ? delegate : new ModelRenderHandle() {
            public BlendModelKey modelKey() { return KEY; }
            public long generation() { return 1; }
            public Bounds bounds() { return delegate.bounds(); }
            public float unitsToBlocksScale() { return 1; }
            public List<PreparedRenderPrimitive> primitives() { return delegate.primitives(); }
            public List<String> materialSlots() { return Collections.nCopies(primitives().size(), "body"); }
            public Transform nodeTransform(int index) { return Transform.IDENTITY; }
            public boolean missingModel() { return false; }
        };
        return new ModelRenderSnapshot(handle, Transform.IDENTITY, 123, 0, 0xffffffff,
                RenderVisibility.VISIBLE, new CullingMetadata(handle.bounds(), true));
    }
}
