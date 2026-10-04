package com.liy.blendlib.fabric.client.item;

import static org.junit.jupiter.api.Assertions.*;
import com.liy.blendlib.api.*;
import com.liy.blendlib.core.animation.runtime.MorphFrameOverrides;
import com.liy.blendlib.core.descriptor.*;
import com.liy.blendlib.core.model.*;
import com.liy.blendlib.fabric.client.animation.ClientAnimationLifecycleBridge;
import com.liy.blendlib.fabric.client.animation.runtime.SkinnedAnimationRuntime;
import com.liy.blendlib.fabric.client.api.BlendLibClientServices;
import com.liy.blendlib.fabric.client.reload.*;
import com.liy.blendlib.fabric.client.render.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Isolated;

@Isolated("Temporarily installs CPU-only client services and restores them")
class StaticItemMorphTest {
    static final BlendModelKey MODEL = BlendModelKey.parse("item_morph:face");
    static final BlendResourceId SQUEEZE = BlendResourceId.parse("item_morph:squeeze");
    // Dedicated marker: animated integration fixtures reserve slime_ball and other vanilla items.
    static final BlendLibItemBinding BINDING = new BlendLibItemBinding(
            Identifier.withDefaultNamespace("honeycomb"), MODEL, Identifier.withDefaultNamespace("item/slime_ball"));
    static final AtomicReference<BlendLibItemMorphControls> CONTROLS = new AtomicReference<>();
    static final BlendLibItemMorphControls DISPATCH = stack -> CONTROLS.get().weights(stack);

    @BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        BlendLibItemMorphs.register(BINDING, DISPATCH);
    }

    @Test void copiedStacksCaptureIndependentlyAndRelightingNeverRecapturesControls() throws Exception {
        withServices((models, lifecycle, runtime) -> {
            var calls = new AtomicInteger();
            var mutable = new HashMap<BlendResourceId, Float>();
            CONTROLS.set(stack -> {
                calls.incrementAndGet(); mutable.put(SQUEEZE, (stack.getCount() - 1) / 63F);
                return new MorphFrameOverrides(mutable);
            });
            var a = stack();
            var b = a.copy(); b.setCount(64);
            var renderer = new BlendLibItemSpecialRenderer(BINDING);
            var first = renderer.extractArgument(a);
            var second = renderer.extractArgument(b);
            assertEquals(0, firstX(first.snapshot(1, 2)), 1e-6);
            assertEquals(1, firstX(second.snapshot(3, 4)), 1e-6);
            a.setCount(32); b.setCount(1); mutable.clear();
            assertEquals(0, firstX(first.snapshot(5, 6)), 1e-6);
            assertEquals(1, firstX(second.snapshot(7, 8)), 1e-6);
            assertSame(StaticItemMorphRenderTestAccess.payload(first.snapshot(1, 2)),
                    StaticItemMorphRenderTestAccess.payload(first.snapshot(5, 6)));
            assertEquals(2, calls.get());
            CONTROLS.set(stack -> MorphFrameOverrides.empty());
            assertEquals(.25, firstX(renderer.extractArgument(a).snapshot(0, 0)), 1e-6);
            assertEquals(0, lifecycle.registry().size());
            assertThrows(IllegalArgumentException.class, () -> BlendLibItemAnimations.playback(a));
            assertTrue(BlendLibItemAnimations.observe(a).isEmpty());
        });
    }

    @Test void staticMorphSnapshotsPreserveAppearanceAndSkinCapture() throws Exception {
        withServices((models, lifecycle, runtime) -> {
            var order = new ArrayList<String>();
            CONTROLS.set(stack -> { order.add("morph"); return new MorphFrameOverrides(Map.of(SQUEEZE, 1F)); });
            var mutable = new HashMap<String, MaterialSlotAppearance>();
            mutable.put("Skin", new MaterialSlotAppearance(0xff0000, true));
            var renderer = new BlendLibItemSpecialRenderer(BINDING,
                    stack -> { order.add("appearance"); return mutable; },
                    stack -> { order.add("skin"); return Optional.empty(); });
            var captured = renderer.extractArgument(stack());
            mutable.clear();
            assertEquals(List.of("morph", "skin", "appearance"), order);
            assertEquals(1, firstX(captured.snapshot(123, 456)), 1e-6);
            assertEquals(123, captured.snapshot(123, 456).packedLight());
            assertEquals(456, captured.snapshot(123, 456).packedOverlay());
            assertTrue(captured.snapshot(123, 456).unknownMaterialSlots().isEmpty());
            assertEquals(3, order.size());
        });
    }

    @Test void callbacksChangingLifecycleOrEmptyingStackDiscardExtraction() throws Exception {
        for (String mutation : List.of("reload", "disconnect", "play_init", "retired", "empty")) {
            withServices((models, lifecycle, runtime) -> {
                var stack = stack();
                CONTROLS.set(s -> new MorphFrameOverrides(Map.of(SQUEEZE, 1F)));
                var renderer = new BlendLibItemSpecialRenderer(BINDING);
                var frozen = renderer.extractArgument(stack);
                var calls = new AtomicInteger();
                CONTROLS.set(s -> {
                    calls.incrementAndGet();
                    switch (mutation) {
                        case "reload" -> models.publish(generation(2));
                        case "disconnect" -> runtime.onWorldDisconnect();
                        case "play_init" -> runtime.onPlayInit();
                        case "retired" -> models.close();
                        case "empty" -> s.setCount(0);
                    }
                    return MorphFrameOverrides.empty();
                });
                assertTrue(renderer.extractArgument(stack).handle().missingModel(), mutation);
                assertEquals(1, calls.get());
                assertEquals(1, firstX(frozen.snapshot(0, 0)), 1e-6);
                assertEquals(0, lifecycle.registry().size());
                CONTROLS.set(s -> MorphFrameOverrides.empty());
                if (!mutation.equals("retired")) {
                    runtime.onPlayInit();
                    assertEquals(.25, firstX(renderer.extractArgument(stack()).snapshot(0, 0)), 1e-6);
                }
            });
        }
    }

    @Test void invalidControlsAndRecursionLeaveNoLatchedGuardOrState() throws Exception {
        withServices((models, lifecycle, runtime) -> {
            var renderer = new BlendLibItemSpecialRenderer(BINDING);
            var stack = stack();
            CONTROLS.set(s -> new MorphFrameOverrides(Map.of(BlendResourceId.parse("item_morph:unknown"), 1F)));
            assertThrows(IllegalArgumentException.class, () -> renderer.extractArgument(stack));
            CONTROLS.set(s -> new MorphFrameOverrides(Map.of(SQUEEZE, -1F)));
            assertThrows(IllegalArgumentException.class, () -> renderer.extractArgument(stack));
            CONTROLS.set(s -> null);
            assertThrows(NullPointerException.class, () -> renderer.extractArgument(stack));
            CONTROLS.set(s -> { throw new IllegalStateException("callback failure"); });
            assertThrows(IllegalStateException.class, () -> renderer.extractArgument(stack));
            var calls = new AtomicInteger();
            CONTROLS.set(s -> {
                calls.incrementAndGet();
                assertTrue(renderer.extractArgument(s.copy()).handle().missingModel());
                return MorphFrameOverrides.empty();
            });
            assertEquals(.25, firstX(renderer.extractArgument(stack).snapshot(0, 0)), 1e-6);
            assertEquals(1, calls.get());
            runtime.onWorldDisconnect();
            assertTrue(renderer.extractArgument(stack).handle().missingModel());
            assertEquals(1, calls.get());
            assertEquals(0, lifecycle.registry().size());
        });
    }

    @Test void modesAreExclusiveAndRegistrationPreservesAppearanceAndControls() {
        assertDoesNotThrow(() -> BlendLibItemMorphs.register(BINDING));
        assertDoesNotThrow(() -> BlendLibItemMorphs.register(BINDING, DISPATCH));
        assertThrows(IllegalStateException.class, () -> BlendLibItemMorphs.register(BINDING, s -> MorphFrameOverrides.empty()));
        assertThrows(NullPointerException.class, () -> BlendLibItemMorphs.register(BINDING, null));
        assertThrows(IllegalStateException.class, () -> BlendLibItemAnimations.register(BINDING, BlendAnimationKey.parse("item_morph:idle")));
        var animated = binding("animated");
        BlendLibItemAnimations.register(animated, BlendAnimationKey.parse("item_morph:idle"));
        assertThrows(IllegalStateException.class, () -> BlendLibItemMorphs.register(animated));
        var appearance = binding("appearance");
        BlendLibItemMaterialAppearance selector = s -> Map.of();
        BlendLibItemModelBindings.register(appearance, selector);
        BlendLibItemMorphs.register(appearance);
        assertDoesNotThrow(() -> BlendLibItemModelBindings.register(appearance, selector));
        assertThrows(IllegalStateException.class, () -> BlendLibItemModelBindings.register(appearance, s -> Map.of()));
        assertEquals(appearance, BlendLibItemModelBindings.find(appearance.itemId()).orElseThrow());
        var conflict = new BlendLibItemBinding(appearance.itemId(), BlendModelKey.parse("item_morph:other"), appearance.baseModelId());
        assertThrows(IllegalStateException.class, () -> BlendLibItemMorphs.register(conflict));
    }

    @Test void statelessFacadeAddsOnlyTwoRegistrationMethodsAndOneFunctionalCallback() {
        assertEquals(Set.of(
                "public static register(Lcom/liy/blendlib/fabric/client/item/BlendLibItemBinding;)V",
                "public static register(Lcom/liy/blendlib/fabric/client/item/BlendLibItemBinding;Lcom/liy/blendlib/fabric/client/item/BlendLibItemMorphControls;)V"),
                ItemAnimationObservationAbiTest.exportedDescriptors(BlendLibItemMorphs.class));
        assertEquals(Set.of("public abstract weights(Lnet/minecraft/world/item/ItemStack;)Lcom/liy/blendlib/core/animation/runtime/MorphFrameOverrides;"),
                ItemAnimationObservationAbiTest.exportedDescriptors(BlendLibItemMorphControls.class));
    }

    private static ItemStack stack() {
        // Real stacks, but no data-pack loading: built-in holder components are not yet bound.
        return new ItemStack(net.minecraft.core.Holder.direct(Items.HONEYCOMB,
                net.minecraft.core.component.DataComponents.COMMON_ITEM_COMPONENTS));
    }

    private static BlendLibItemBinding binding(String name) {
        return new BlendLibItemBinding(Identifier.fromNamespaceAndPath("item_morph", name), MODEL,
                Identifier.withDefaultNamespace("item/slime_ball"));
    }
    private static float firstX(ModelRenderSnapshot frame) {
        return StaticItemMorphRenderTestAccess.firstX(frame);
    }
    private static ModelRegistryGeneration generation(long id) {
        var geometry = new MeshPrimitive("Skin", new float[]{0,0,0,1,0,0,0,1,0}, new float[]{0,0,1,0,0,1,0,0,1},
                new float[]{0,0,1,0,0,1}, new int[]{0,1,2}, new int[12], new float[]{1,0,0,0,1,0,0,0,1,0,0,0});
        var bindings = new MorphBindingTable(List.of(new MorphBindingTable.Binding(0, List.of("Squeeze"), 0,
                new float[]{.25F}, new float[]{0}, new float[]{1})),
                Map.of(SQUEEZE, new MorphBindingTable.Control(0,0,0,0,1)));
        var target = new MorphTargetSet(List.of("Squeeze"), 3,
                new float[][]{{1,0,0,1,0,0,1,0,0}}, new float[][]{new float[9]});
        var asset = new ModelAsset(MODEL.resourceId(), MODEL.descriptorResourceId(), id, ModelProfile.SKINNED_MORPH_CPU_V1, 1,
                Map.of("Skin", new MaterialDefinition(BlendResourceId.parse("item_morph:textures/skin.png"), MaterialDefinition.Mode.OPAQUE, false, false, null)),
                null, List.of(new ModelNode(0,"Mesh",Transform.IDENTITY,List.of(1),0,0,false),
                        new ModelNode(1,"Bone",Transform.IDENTITY,List.of(),-1,-1,false)), List.of(0),
                List.of(new ModelPrimitive(0,0,0,geometry)),
                new Skeleton(List.of(new Skin("Rig",1,List.of(1),new float[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1}))),
                List.of(), new SocketTable(Map.of()), Bounds.fromPositions(geometry.positions()), List.of(), bindings, Map.of(geometry,target));
        return new ModelRegistryGeneration(id, Map.of(MODEL,
                new LoadedModelHandle(MODEL, asset, SkinnedRenderHandle.prepare(MODEL, asset))), Map.of(), List.of());
    }
    private interface Check { void run(ClientModelRegistry models, ClientAnimationLifecycleBridge lifecycle,
            SkinnedAnimationRuntime runtime) throws Exception; }
    @SuppressWarnings("unchecked")
    private static void withServices(Check check) throws Exception {
        var field = BlendLibClientServices.class.getDeclaredField("ACTIVE"); field.setAccessible(true);
        var active = (AtomicReference<Object>) field.get(null); Object previous = active.getAndSet(null);
        var models = new ClientModelRegistry(); var lifecycle = new ClientAnimationLifecycleBridge(2);
        var runtime = new SkinnedAnimationRuntime(models, lifecycle); runtime.onPlayInit(); models.publish(generation(1));
        try {
            BlendLibClientServices.initialize(models, (snapshot, context) -> { }, runtime);
            check.run(models, lifecycle, runtime);
        } finally { CONTROLS.set(null); runtime.onWorldDisconnect(); models.close(); active.set(previous); }
    }
}
