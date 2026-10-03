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

    @Test
    void skinRunsOnceBeforeAppearanceAndSnapshotSelectionIsFrozen() {
        var selected = new java.util.concurrent.atomic.AtomicReference<>(
                Optional.of(com.liy.blendlib.api.BlendResourceId.parse("appearance:blue")));
        var order = new ArrayList<String>();
        var base = snapshot(false);
        BlendEntitySnapshotFactory<Entity> factory = (entity, request) -> base;
        var capturedFactory = BlendEntityRendererBuilder.captureMaterialAppearance(
                BlendEntityRendererBuilder.captureSkin(factory, (entity, request) -> {
                    assertSame(REQUEST, request); order.add("skin"); return selected.get();
                }), (entity, request) -> { order.add("appearance"); return Map.of(); });
        var captured = capturedFactory.create(null, REQUEST);
        assertEquals(List.of("skin", "appearance"), order);
        assertTrue(captured.skinDiagnostic().isPresent(), "Unregistered skin must diagnose fallback");
        selected.set(Optional.empty());
        var relit = captured.withLighting(4, 5);
        assertEquals(captured.skinDiagnostic(), relit.skinDiagnostic());
        assertEquals(captured.selectedSkin(), relit.selectedSkin());
        assertEquals(List.of("skin", "appearance"), order);
        assertTrue(capturedFactory.create(null, REQUEST).skinDiagnostic().isEmpty());
        assertTrue(base.skinDiagnostic().isEmpty());
    }

    @Test
    void skinBypassesMissingAndAbsentSnapshotsAndSupportsEveryBuilderPath() {
        BlendEntitySkinSelector<Entity> unexpected = (entity, request) -> {
            fail("Diagnostic and absent snapshots bypass skin selectors"); return Optional.empty();
        };
        assertNull(BlendEntityRendererBuilder.captureSkin(
                (Entity entity, BlendEntitySnapshotRequest request) -> null, unexpected).create(null, REQUEST));
        var missing = snapshot(true);
        assertSame(missing, BlendEntityRendererBuilder.captureSkin(
                (Entity entity, BlendEntitySnapshotRequest request) -> missing, unexpected).create(null, REQUEST));
        var ordinary = snapshot(false);
        assertSame(ordinary, BlendEntityRendererBuilder.captureSkin(
                (Entity entity, BlendEntitySnapshotRequest request) -> ordinary, null).create(null, REQUEST));
        for (var builder : List.of(builder(), builder().staticRestPose(),
                builder().snapshotFactory((entity, request) -> null),
                builder().skinnedAnimation((entity, request) -> BlendAnimationKey.parse("appearance:idle")),
                builder().synchronizedSkinnedAnimation((entity, request) -> BlendAnimationKey.parse("appearance:idle")))) {
            assertSame(builder, builder.skin((entity, request) -> Optional.empty()));
            assertThrows(NullPointerException.class, () -> builder.skin(null));
        }
    }

    @Test
    void separateEntityExtractionsFreezeDifferentValidNamedSkinsOnTheSameHandle() {
        var blue = com.liy.blendlib.api.BlendResourceId.parse("appearance:blue");
        var gold = com.liy.blendlib.api.BlendResourceId.parse("appearance:gold");
        var base = preparedSkinSnapshot(Map.of(
                blue, Map.of("body", com.liy.blendlib.api.BlendResourceId.parse("appearance:textures/blue.png")),
                gold, Map.of("body", com.liy.blendlib.api.BlendResourceId.parse("appearance:textures/gold.png"))));
        var selected = new java.util.concurrent.atomic.AtomicReference<>(Optional.of(blue));
        var calls = new AtomicInteger();
        var factory = BlendEntityRendererBuilder.captureSkin(
                (Entity entity, BlendEntitySnapshotRequest request) -> base, (entity, request) -> {
                    calls.incrementAndGet(); return selected.get();
                });
        var first = factory.create(null, REQUEST);
        selected.set(Optional.of(gold));
        var second = factory.create(null, REQUEST);
        assertSame(first.handle(), second.handle());
        assertEquals(Optional.of(blue), first.selectedSkin());
        assertEquals(Optional.of(gold), second.selectedSkin());
        assertTrue(first.skinDiagnostic().isEmpty());
        assertTrue(second.skinDiagnostic().isEmpty());
        assertTrue(base.selectedSkin().isEmpty());
        assertEquals(2, calls.get());
    }

    private static ModelRenderSnapshot preparedSkinSnapshot(Map<com.liy.blendlib.api.BlendResourceId,
            Map<String, com.liy.blendlib.api.BlendResourceId>> definitions) {
        var mesh = new com.liy.blendlib.core.model.MeshPrimitive("body",
                new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0}, new float[]{0, 0, 1, 0, 0, 1, 0, 0, 1},
                new float[]{0, 0, 1, 0, 0, 1}, new int[]{0, 1, 2}, null, null);
        var asset = new com.liy.blendlib.core.model.ModelAsset(KEY.resourceId(), KEY.descriptorResourceId(), 8,
                com.liy.blendlib.core.model.ModelProfile.RIGID_V1, 1,
                Map.of("body", new com.liy.blendlib.core.descriptor.MaterialDefinition(
                        com.liy.blendlib.api.BlendResourceId.parse("appearance:textures/authored.png"),
                        com.liy.blendlib.core.descriptor.MaterialDefinition.Mode.OPAQUE, false, false, null)),
                null, List.of(new com.liy.blendlib.core.model.ModelNode(0, "mesh", Transform.IDENTITY, List.of(), 0, -1, false)),
                List.of(0), List.of(new com.liy.blendlib.core.model.ModelPrimitive(0, 0, 0, mesh)), null,
                List.of(), new com.liy.blendlib.core.model.SocketTable(Map.of()),
                Bounds.fromPositions(new float[]{0, 0, 0, 1, 1, 1}), List.of());
        var handle = StaticRigidRenderHandle.prepareWithSkins(KEY, asset, definitions, Map.of());
        return new ModelRenderSnapshot(handle, Transform.IDENTITY, 1, 2, 0xffffffff,
                RenderVisibility.VISIBLE, new CullingMetadata(handle.bounds(), true));
    }

    @Test
    void skinCallbackPinsExactPublicSignature() throws ReflectiveOperationException {
        assertTrue(BlendEntitySkinSelector.class.isAnnotationPresent(FunctionalInterface.class));
        var select = BlendEntitySkinSelector.class.getDeclaredMethod("select", Entity.class, BlendEntitySnapshotRequest.class);
        assertEquals("java.util.Optional<com.liy.blendlib.api.BlendResourceId>", select.getGenericReturnType().getTypeName());
        assertEquals(1, BlendEntitySkinSelector.class.getDeclaredMethods().length);
        assertEquals(BlendEntityRendererBuilder.class,
                BlendEntityRendererBuilder.class.getMethod("skin", BlendEntitySkinSelector.class).getReturnType());
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
