package com.liy.blendlib.fabric.client.item;

import com.liy.blendlib.api.BlendModelKey;
import com.mojang.blaze3d.vertex.PoseStack;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemTransform;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.item.SpecialModelWrapper;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Checks the native 1.21.4 baked-model stage and native display transform execution, without a window. */
class LegacyItemModelTest {
    @Test void markerLookupUsesTheCurrentBakedGenerationAndLeavesOtherItemsAlone() throws Exception {
        ResourceLocation marker = ResourceLocation.parse("test:reload_marker");
        ResourceLocation baseId = ResourceLocation.parse("test:item/base");
        BlendLibItemModelBindings.register(new BlendLibItemBinding(marker, BlendModelKey.parse("test:model"), baseId));
        BakedModel first = new BaseModel(ItemTransforms.NO_TRANSFORMS);
        BakedModel second = new BaseModel(ItemTransforms.NO_TRANSFORMS);
        AtomicReference<BakedModel> generation = new AtomicReference<>(first);
        java.util.function.Function<ResourceLocation, BakedModel> baked = id -> {
            assertEquals(baseId, id);
            return generation.get();
        };
        ItemModel oldWrapper = BlendLibItemModelBindings.registeredMarkerModel(marker, baked);
        assertInstanceOf(SpecialModelWrapper.class, oldWrapper);
        Field baseField = SpecialModelWrapper.class.getDeclaredField("baseModel");
        baseField.setAccessible(true);
        assertSame(first, baseField.get(oldWrapper));
        generation.set(second);
        ItemModel newWrapper = BlendLibItemModelBindings.registeredMarkerModel(marker, baked);
        assertSame(second, baseField.get(newWrapper));
        assertSame(first, baseField.get(oldWrapper), "Already extracted model state must remain immutable");
        assertNull(BlendLibItemModelBindings.registeredMarkerModel(ResourceLocation.parse("test:unbound"), id -> {
            fail("Unregistered items must not ask for any base model"); return null;
        }));
    }

    @Test void nativeSpecialLayerAppliesBaseDisplayTransformAndRestoresPoseForBothHands() throws Exception {
        ItemTransform transform = new ItemTransform(new Vector3f(), new Vector3f(2, 3, 4), new Vector3f(1));
        ItemTransforms transforms = new ItemTransforms(transform, transform, transform, transform,
                transform, transform, transform, transform);
        BakedModel base = new BaseModel(transforms);
        for (boolean left : new boolean[] {false, true}) {
            ItemStackRenderState state = new ItemStackRenderState();
            set(state, "displayContext", ItemDisplayContext.GUI);
            set(state, "isLeftHand", left);
            AtomicReference<float[]> observed = new AtomicReference<>();
            SpecialModelRenderer<String> renderer = new SpecialModelRenderer<>() {
                @Override public String extractArgument(ItemStack stack) { throw new AssertionError("Already captured"); }
                @Override public void render(String argument, ItemDisplayContext context, PoseStack poses,
                        MultiBufferSource buffers, int light, int overlay, boolean foil) {
                    assertEquals("captured", argument);
                    assertEquals(ItemDisplayContext.GUI, context);
                    assertEquals(17, light); assertEquals(23, overlay);
                    observed.set(new float[] {poses.last().pose().m30(), poses.last().pose().m31(), poses.last().pose().m32()});
                }
            };
            state.newLayer().setupSpecialModel(renderer, "captured", base);
            PoseStack poses = new PoseStack();
            poses.translate(10, 20, 30);
            state.render(poses, type -> { fail("Native special layers must use their custom renderer"); return null; }, 17, 23);
            assertArrayEquals(new float[] {left ? 7.5f : 11.5f, 22.5f, 33.5f}, observed.get(), 0.00001f);
            assertEquals(10f, poses.last().pose().m30());
            assertEquals(20f, poses.last().pose().m31());
            assertEquals(30f, poses.last().pose().m32());
        }
    }

    private static void set(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true); field.set(object, value);
    }
    private record BaseModel(ItemTransforms transforms) implements BakedModel {
        @Override public List<BakedQuad> getQuads(BlockState state, Direction direction, RandomSource random) { return List.of(); }
        @Override public boolean useAmbientOcclusion() { return false; }
        @Override public boolean isGui3d() { return true; }
        @Override public boolean usesBlockLight() { return false; }
        @Override public TextureAtlasSprite getParticleIcon() { return null; }
        @Override public ItemTransforms getTransforms() { return transforms; }
    }
}
