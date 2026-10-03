package com.liy.blendlib.fabric.client.item;

import static org.junit.jupiter.api.Assertions.*;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.runtime.*;
import com.liy.blendlib.core.descriptor.MaterialDefinition;
import com.liy.blendlib.core.model.*;
import com.liy.blendlib.fabric.client.render.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

class ItemAnimationMaterialAppearanceTest {
    private static final BlendModelKey KEY = BlendModelKey.parse("appearance:item_capture");
    private static final BlendLibItemBinding BINDING = binding("capture");
    private static final MaterialSlotAppearance RED = new MaterialSlotAppearance(0xff0000, true);
    private static BlendLibItemBinding binding(String name) {
        return new BlendLibItemBinding(Identifier.fromNamespaceAndPath("appearance", name), KEY,
                Identifier.withDefaultNamespace("item/stick"));
    }

    @Test void captureFreezesSelectionAndRelightingPreservesRigidAndCpuSkinnedFrames() {
        for (boolean skinned : List.of(false, true)) {
            var asset = asset(8, skinned, "body", "eye");
            var skinId = BlendResourceId.parse("appearance:blue");
            var skinDefinitions = Map.of(skinId, Map.of("body", BlendResourceId.parse("appearance:textures/blue.png")));
            ModelRenderHandle handle = skinned ? SkinnedRenderHandle.prepareWithSkins(KEY, asset, skinDefinitions, Map.of())
                    : StaticRigidRenderHandle.prepareWithSkins(KEY, asset, skinDefinitions, Map.of());
            var root = new Transform(new Vec3(2, 3, 4), Quaternion.IDENTITY, Vec3.ONE);
            ModelRenderSnapshot base;
            if (skinned) {
                var skin = asset.skeleton().skins().getFirst();
                var palette = SkinPalette.from(skin, NodePalette.from(new LocalPose(
                        Map.of(0, Transform.IDENTITY, 1, Transform.IDENTITY)), asset.nodes()));
                var skinnedHandle = (SkinnedRenderHandle) handle;
                var output = SkinnedRenderSnapshot.capture(skinnedHandle, skinnedHandle.skinnedPrimitives().stream()
                        .map(primitive -> CpuSkinner.skin(primitive.geometry(), palette)).toList());
                base = ModelRenderSnapshot.skinned(skinnedHandle, root, 12, 34, 0x80706050,
                        RenderVisibility.VISIBLE, new CullingMetadata(handle.bounds(), true), output);
            } else {
                base = ModelRenderSnapshot.rigid((StaticRigidRenderHandle) handle, root, 12, 34, 0x80706050,
                        RenderVisibility.VISIBLE, new CullingMetadata(handle.bounds(), true), Map.of(0, root));
            }
            var selections = new AtomicInteger();
            var observations = new AtomicInteger();
            var skinSelections = new AtomicInteger();
            var skinObservations = new AtomicInteger();
            var selectedSkin = new java.util.concurrent.atomic.AtomicReference<>(Optional.of(skinId));
            var mutable = new HashMap<>(Map.of("body", RED));
            var argument = BlendLibItemRenderArgument.capture(BINDING, handle, base, null,
                    new BlendLibItemMaterialAppearance() {
                        public Map<String, MaterialSlotAppearance> select(net.minecraft.world.item.ItemStack stack) {
                            selections.incrementAndGet(); return mutable;
                        }
                        public void captured(net.minecraft.world.item.ItemStack stack, ModelRenderSnapshot snapshot) {
                            observations.incrementAndGet();
                            assertEquals(RED, appearance(snapshot, 0));
                            assertEquals(Optional.of(skinId), snapshot.selectedSkin());
                        }
                    }, new BlendLibItemSkinSelector() {
                        public Optional<BlendResourceId> select(net.minecraft.world.item.ItemStack stack) {
                            skinSelections.incrementAndGet(); return selectedSkin.get();
                        }
                        public void captured(net.minecraft.world.item.ItemStack stack, ModelRenderSnapshot snapshot) {
                            skinObservations.incrementAndGet();
                            assertEquals(Optional.of(skinId), snapshot.selectedSkin());
                            assertEquals(RED, appearance(snapshot, 0));
                        }
                    });
            selectedSkin.set(Optional.empty());
            mutable.clear();
            mutable.put("unknown", RED);
            for (int i = 0; i < 3; i++) {
                var frame = argument.snapshot(56 + i, 78 + i);
                assertSame(handle, frame.handle());
                assertSame(root, frame.rootTransform());
                assertSame(base.culling(), frame.culling());
                assertSame(base.visibility(), frame.visibility());
                assertEquals(base.tintArgb(), frame.tintArgb());
                assertSame(frameField(base, "skinnedRenderSnapshot"), frameField(frame, "skinnedRenderSnapshot"));
                assertSame(frameField(base, "rigidNodePalette"), frameField(frame, "rigidNodePalette"));
                assertEquals(56 + i, frame.packedLight());
                assertEquals(78 + i, frame.packedOverlay());
                assertEquals(RED, appearance(frame, 0));
                assertEquals(MaterialSlotAppearance.unchanged(), appearance(frame, 1));
                assertTrue(frame.unknownMaterialSlots().isEmpty());
                assertEquals(Optional.of(skinId), frame.selectedSkin());
                assertTrue(frame.skinDiagnostic().isEmpty());
            }
            assertEquals(1, skinSelections.get());
            assertEquals(1, skinObservations.get());
            assertEquals(1, selections.get());
            assertEquals(1, observations.get());
            assertEquals(12, base.packedLight());
            assertEquals(MaterialSlotAppearance.unchanged(), appearance(base, 0));
        }
    }

    @Test void unknownSlotsFallBackAtomicallyAndObserverSeesFrozenSortedDiagnostics() {
        var handle = rigid(1, "body", "eye");
        var seen = new ArrayList<ModelRenderSnapshot>();
        var argument = BlendLibItemRenderArgument.capture(BINDING, handle, null, null,
                new BlendLibItemMaterialAppearance() {
                    public Map<String, MaterialSlotAppearance> select(net.minecraft.world.item.ItemStack stack) {
                        return Map.of("body", RED, "z", RED, "Body", RED);
                    }
                    public void captured(net.minecraft.world.item.ItemStack stack, ModelRenderSnapshot snapshot) {
                        seen.add(snapshot);
                    }
                });
        assertEquals(1, seen.size());
        assertEquals(List.of("Body", "z"), seen.getFirst().unknownMaterialSlots());
        assertThrows(UnsupportedOperationException.class, () -> seen.getFirst().unknownMaterialSlots().clear());
        for (int i = 0; i < 2; i++)
            assertEquals(MaterialSlotAppearance.unchanged(), appearance(argument.snapshot(1, 2), i));
        assertEquals(seen.getFirst().unknownMaterialSlots(), argument.snapshot(1, 2).unknownMaterialSlots());
    }

    @Test void missingModelsBypassBothCallbacksAndExactHandleIdentityIsRequiredBeforeSelection() {
        BlendLibItemMaterialAppearance unexpected = new BlendLibItemMaterialAppearance() {
            public Map<String, MaterialSlotAppearance> select(net.minecraft.world.item.ItemStack stack) {
                fail("selection must not run"); return Map.of();
            }
            public void captured(net.minecraft.world.item.ItemStack stack, ModelRenderSnapshot snapshot) {
                fail("observer must not run");
            }
        };
        var missing = new MissingModelRenderHandle(KEY, 1);
        var argument = BlendLibItemRenderArgument.capture(BINDING, missing, null, null, unexpected);
        assertSame(missing, argument.snapshot(3, 4).handle());
        var handle = rigid(7, "body");
        for (long generation : List.of(7L, 8L)) {
            assertThrows(IllegalArgumentException.class, () -> BlendLibItemRenderArgument.capture(
                    BINDING, rigid(generation, "body"), snapshot(handle), null, unexpected));
        }
        assertNull(BlendLibItemRenderArgument.capture(BINDING, handle, null, null, null).animatedSnapshot());
    }

    @Test void registrationPreservesConfiguredSelectorRejectsConflictsAndKeepsOldBakeConfiguration() throws Exception {
        var binding = binding("registration_lifecycle");
        BlendLibItemMaterialAppearance first = stack -> Map.of("body", RED);
        BlendLibItemMaterialAppearance second = stack -> Map.of();
        BlendLibItemModelBindings.register(binding);
        var plain = replacement(binding);
        BlendLibItemModelBindings.register(binding, first);
        var configured = replacement(binding);
        BlendLibItemModelBindings.register(binding);
        BlendLibItemModelBindings.register(binding, first);
        assertThrows(IllegalStateException.class, () -> BlendLibItemModelBindings.register(binding, second));
        assertThrows(IllegalStateException.class, () -> BlendLibItemModelBindings.register(new BlendLibItemBinding(
                binding.itemId(), BlendModelKey.parse("appearance:other"), binding.baseModelId()), first));
        assertThrows(NullPointerException.class, () -> BlendLibItemModelBindings.register(binding, null));
        var field = BlendLibItemSpecialRenderer.Unbaked.class.getDeclaredField("appearance");
        field.setAccessible(true);
        assertNull(field.get(plain), "previously selected unbaked model retains old configuration");
        assertSame(first, field.get(configured));
        assertSame(first, field.get(replacement(binding)));
        assertEquals(binding, BlendLibItemModelBindings.find(binding.itemId()).orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> BlendLibItemModelBindings.bindings().clear());
    }

    @Test void staticItemCapturesValidSkinWithoutChangingAuthoredSnapshotOrHandle() {
        var skinId = BlendResourceId.parse("appearance:static_blue");
        var handle = StaticRigidRenderHandle.prepareWithSkins(KEY, asset(43, false, "body"),
                Map.of(skinId, Map.of("body", BlendResourceId.parse("appearance:textures/blue.png"))), Map.of());
        var selected = new java.util.concurrent.atomic.AtomicReference<>(Optional.of(skinId));
        var calls = new AtomicInteger();
        var argument = BlendLibItemRenderArgument.capture(BINDING, handle, null, null, null, stack -> {
            calls.incrementAndGet(); return selected.get();
        });
        selected.set(Optional.empty());
        for (int i = 0; i < 3; i++) {
            var frame = argument.snapshot(i, i + 1);
            assertSame(handle, frame.handle());
            assertEquals(Optional.of(skinId), frame.selectedSkin());
            assertTrue(frame.skinDiagnostic().isEmpty());
            assertEquals(MaterialSlotAppearance.unchanged(), appearance(frame, 0));
        }
        assertEquals(1, calls.get());
        assertTrue(snapshot(handle).selectedSkin().isEmpty());
    }

    @Test void namedSkinRegistrationPreservesBothCallbacksAndOldConstructors() throws Exception {
        BlendLibItemMaterialAppearance appearance = stack -> Map.of();
        BlendLibItemSkinSelector skin = stack -> Optional.empty();
        BlendLibItemSkinSelector other = stack -> Optional.of(BlendResourceId.parse("appearance:other"));
        var appearanceField = BlendLibItemSpecialRenderer.Unbaked.class.getDeclaredField("appearance");
        var skinField = BlendLibItemSpecialRenderer.Unbaked.class.getDeclaredField("skin");
        appearanceField.setAccessible(true);
        skinField.setAccessible(true);
        for (boolean skinFirst : List.of(false, true)) {
            var binding = binding("skin_registration_" + skinFirst);
            if (skinFirst) BlendLibItemModelBindings.registerWithSkin(binding, skin);
            else BlendLibItemModelBindings.register(binding, appearance);
            var before = replacement(binding);
            BlendLibItemModelBindings.registerWithSkin(binding, appearance, skin);
            BlendLibItemModelBindings.register(binding);
            BlendLibItemModelBindings.register(binding, appearance);
            BlendLibItemModelBindings.registerWithSkin(binding, skin);
            var after = replacement(binding);
            assertSame(appearance, appearanceField.get(after));
            assertSame(skin, skinField.get(after));
            assertNull((skinFirst ? appearanceField : skinField).get(before));
            assertThrows(IllegalStateException.class, () -> BlendLibItemModelBindings.registerWithSkin(binding, other));
            assertThrows(IllegalStateException.class, () -> BlendLibItemModelBindings.registerWithSkin(
                    binding, stack -> Map.of(), skin));
            assertSame(appearance, appearanceField.get(replacement(binding)), "Conflict is atomic");
            assertSame(skin, skinField.get(replacement(binding)), "Conflict is atomic");
            assertThrows(NullPointerException.class, () -> BlendLibItemModelBindings.registerWithSkin(binding, null));
            assertThrows(NullPointerException.class, () -> BlendLibItemModelBindings.registerWithSkin(binding, null, skin));
            assertThrows(NullPointerException.class, () -> BlendLibItemModelBindings.registerWithSkin(binding, appearance, null));
        }
        assertNull(skinField.get(new BlendLibItemSpecialRenderer.Unbaked(BINDING)));
        assertNull(skinField.get(new BlendLibItemSpecialRenderer.Unbaked(BINDING, appearance)));
        assertNotNull(new BlendLibItemSpecialRenderer(BINDING));
        assertNotNull(new BlendLibItemSpecialRenderer(BINDING, appearance));
    }

    @Test void skinAndAppearanceObserveOneCompletedStaticCaptureAndNeverRunDuringRelighting() {
        var order = new ArrayList<String>();
        var selected = new java.util.concurrent.atomic.AtomicReference<>(Optional.of(BlendResourceId.parse("appearance:blue")));
        var skin = new BlendLibItemSkinSelector() {
            public Optional<BlendResourceId> select(net.minecraft.world.item.ItemStack stack) {
                order.add("skin"); return selected.get();
            }
            public void captured(net.minecraft.world.item.ItemStack stack, ModelRenderSnapshot frame) {
                order.add("skin-observer");
                assertTrue(frame.skinDiagnostic().isPresent());
                assertEquals(RED, appearance(frame, 0));
            }
        };
        var material = new BlendLibItemMaterialAppearance() {
            public Map<String, MaterialSlotAppearance> select(net.minecraft.world.item.ItemStack stack) {
                order.add("appearance"); return Map.of("body", RED);
            }
            public void captured(net.minecraft.world.item.ItemStack stack, ModelRenderSnapshot frame) {
                order.add("appearance-observer"); assertTrue(frame.skinDiagnostic().isPresent());
            }
        };
        var handle = rigid(42, "body");
        var captured = BlendLibItemRenderArgument.capture(BINDING, handle, null, null, material, skin);
        selected.set(Optional.empty());
        for (int i = 0; i < 3; i++) {
            var frame = captured.snapshot(i, i + 1);
            assertTrue(frame.skinDiagnostic().isPresent());
            assertEquals(RED, appearance(frame, 0));
            assertEquals(i, frame.packedLight());
            assertEquals(i + 1, frame.packedOverlay());
        }
        assertEquals(List.of("skin", "appearance", "skin-observer", "appearance-observer"), order);
    }

    @Test void missingAndWrongHandleBypassSkinSelectionAndObservation() {
        var unexpected = new BlendLibItemSkinSelector() {
            public Optional<BlendResourceId> select(net.minecraft.world.item.ItemStack stack) {
                fail("Must bypass skin selection"); return Optional.empty();
            }
            public void captured(net.minecraft.world.item.ItemStack stack, ModelRenderSnapshot frame) {
                fail("Must bypass skin observation");
            }
        };
        var missing = new MissingModelRenderHandle(KEY, 1);
        var captured = BlendLibItemRenderArgument.capture(BINDING, missing, null, null, null, unexpected);
        assertSame(missing, captured.snapshot(1, 2).handle());
        var handle = rigid(1, "body");
        assertThrows(IllegalArgumentException.class, () -> BlendLibItemRenderArgument.capture(
                BINDING, rigid(1, "body"), snapshot(handle), null, null, unexpected));
        assertThrows(IllegalArgumentException.class, () -> BlendLibItemRenderArgument.capture(
                BINDING, rigid(2, "body"), snapshot(handle), null, null, unexpected));
    }

    private static BlendLibItemSpecialRenderer.Unbaked replacement(BlendLibItemBinding binding) {
        var incoming = new net.minecraft.client.renderer.item.SpecialModelWrapper.Unbaked(
                binding.baseModelId(), Optional.empty(), new BlendLibItemSpecialRenderer.Unbaked(binding));
        var context = (net.fabricmc.fabric.api.client.model.loading.v1.ModelModifier.BeforeBakeItem.Context)
                java.lang.reflect.Proxy.newProxyInstance(ItemAnimationMaterialAppearanceTest.class.getClassLoader(),
                new Class<?>[]{net.fabricmc.fabric.api.client.model.loading.v1.ModelModifier.BeforeBakeItem.Context.class},
                (proxy, method, args) -> method.getName().equals("itemId") ? binding.itemId() : null);
        return (BlendLibItemSpecialRenderer.Unbaked) ((net.minecraft.client.renderer.item.SpecialModelWrapper.Unbaked)
                BlendLibItemModelBindings.replaceRegisteredMarker(incoming, context)).specialModel();
    }

    private static ModelRenderSnapshot snapshot(ModelRenderHandle handle) {
        return new ModelRenderSnapshot(handle, Transform.IDENTITY, 123, 456, 0xffffffff,
                RenderVisibility.VISIBLE, new CullingMetadata(handle.bounds(), true));
    }

    private static StaticRigidRenderHandle rigid(long generation, String... slots) {
        return StaticRigidRenderHandle.prepare(KEY, asset(generation, false, slots));
    }

    private static ModelAsset asset(long generation, boolean skinned, String... slots) {
        var nodes = skinned ? List.of(
                new ModelNode(0, "mesh", Transform.IDENTITY, List.of(1), 0, 0, false),
                new ModelNode(1, "joint", Transform.IDENTITY, List.of(), -1, -1, false))
                : List.of(new ModelNode(0, "mesh", Transform.IDENTITY, List.of(), 0, -1, false));
        var materials = new HashMap<String, MaterialDefinition>();
        var primitives = new ArrayList<ModelPrimitive>();
        for (String slot : slots) {
            materials.put(slot, new MaterialDefinition(BlendResourceId.parse("appearance:textures/test.png"),
                    MaterialDefinition.Mode.OPAQUE, false, false, null));
            var mesh = new MeshPrimitive(slot, new float[]{0,0,0, 1,0,0, 0,1,0},
                    new float[]{0,0,1, 0,0,1, 0,0,1}, new float[]{0,0, 1,0, 0,1}, new int[]{0,1,2},
                    skinned ? new int[12] : null,
                    skinned ? new float[]{1,0,0,0, 1,0,0,0, 1,0,0,0} : null);
            primitives.add(new ModelPrimitive(0, 0, primitives.size(), mesh));
        }
        var skeleton = skinned ? new Skeleton(List.of(new Skin("skin", 1, List.of(1),
                new float[]{1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1}))) : null;
        return new ModelAsset(KEY.resourceId(), KEY.descriptorResourceId(), generation,
                skinned ? ModelProfile.SKINNED_V1 : ModelProfile.RIGID_V1, 1,
                materials, null, nodes, List.of(0), primitives, skeleton, List.of(), new SocketTable(Map.of()),
                Bounds.fromPositions(new float[]{0,0,0, 1,1,1}), List.of());
    }
    private static com.liy.blendlib.fabric.client.render.MaterialSlotAppearance appearance(
            com.liy.blendlib.fabric.client.render.ModelRenderSnapshot snapshot, int primitive) {
        try {
            var method = snapshot.getClass().getDeclaredMethod("materialAppearance", int.class);
            method.setAccessible(true);
            return (com.liy.blendlib.fabric.client.render.MaterialSlotAppearance) method.invoke(snapshot, primitive);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
    private static Object frameField(com.liy.blendlib.fabric.client.render.ModelRenderSnapshot snapshot, String name) {
        try {
            var method = snapshot.getClass().getDeclaredMethod(name);
            method.setAccessible(true);
            return method.invoke(snapshot);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
