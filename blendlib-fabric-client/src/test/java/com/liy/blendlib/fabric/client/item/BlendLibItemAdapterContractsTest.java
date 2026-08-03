package com.liy.blendlib.fabric.client.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.fabric.client.render.MissingModelRenderHandle;
import com.mojang.serialization.MapCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelModifier;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.SpecialModelWrapper;
import net.minecraft.client.resources.model.ResolvableModel;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

class BlendLibItemAdapterContractsTest {
    private static final BlendModelKey MODEL_KEY = BlendModelKey.parse("item_test:fixtures/static_model");
    private static final BlendLibItemBinding BINDING = new BlendLibItemBinding(
            Identifier.fromNamespaceAndPath("item_test", "marker"),
            MODEL_KEY,
            Identifier.withDefaultNamespace("item/stick"));

    @Test
    void extractedArgumentBindsOnePreparedGenerationAndBuildsOnlyASnapshot() {
        MissingModelRenderHandle handle = new MissingModelRenderHandle(MODEL_KEY, 7L);
        BlendLibItemRenderArgument argument = new BlendLibItemRenderArgument(BINDING, handle);

        assertEquals(BINDING, argument.binding());
        assertEquals(handle, argument.handle());
        assertEquals(handle, argument.snapshot(0x00F000F0, 3).handle());
        assertEquals(7L, argument.snapshot(0x00F000F0, 3).generation());
        assertEquals(0x00F000F0, argument.snapshot(0x00F000F0, 3).packedLight());
        assertEquals(3, argument.snapshot(0x00F000F0, 3).packedOverlay());

        MissingModelRenderHandle mismatched = new MissingModelRenderHandle(
                BlendModelKey.parse("item_test:fixtures/other"), 7L);
        assertThrows(IllegalArgumentException.class, () -> new BlendLibItemRenderArgument(BINDING, mismatched));
    }

    @Test
    void programmaticUnbakedRendererHasAUnitCodecWithoutRegisteringCustomJsonType() {
        BlendLibItemSpecialRenderer.Unbaked unbaked = new BlendLibItemSpecialRenderer.Unbaked(BINDING);
        assertNotNull(unbaked.type());
    }

    @Test
    void beforeBakeHookReplacesOnlyAnExplicitlyRegisteredMarkerItem() {
        Identifier registeredId = Identifier.fromNamespaceAndPath("item_test", "registered_marker");
        BlendLibItemBinding registered = new BlendLibItemBinding(
                registeredId, MODEL_KEY, Identifier.withDefaultNamespace("item/stick"));
        BlendLibItemModelBindings.register(registered);

        ItemModel.Unbaked incoming = new TestUnbaked();
        ItemModel.Unbaked replacement = BlendLibItemModelBindings.replaceRegisteredMarker(
                incoming, new TestBeforeBakeItemContext(registeredId));
        assertTrue(replacement instanceof SpecialModelWrapper.Unbaked);
        SpecialModelWrapper.Unbaked wrapper = (SpecialModelWrapper.Unbaked) replacement;
        assertEquals(registered.baseModelId(), wrapper.base());
        assertTrue(wrapper.transformation().isEmpty());
        assertTrue(wrapper.specialModel() instanceof BlendLibItemSpecialRenderer.Unbaked);

        ItemModel.Unbaked untouched = BlendLibItemModelBindings.replaceRegisteredMarker(
                incoming, new TestBeforeBakeItemContext(Identifier.fromNamespaceAndPath("item_test", "unregistered_marker")));
        assertEquals(incoming, untouched);
    }

    @Test
    void markerHookIsExplicitAndSubmitSourceHasNoLookupOrAssetIo() throws IOException {
        String bindings = Files.readString(clientSource("item/BlendLibItemModelBindings.java"));
        assertTrue(bindings.contains("modifyItemModelBeforeBake"));
        assertTrue(bindings.contains("new SpecialModelWrapper.Unbaked"));
        assertFalse(bindings.contains("SpecialModelRenderers."));
        assertFalse(bindings.contains("Class.forName"));
        assertFalse(bindings.contains("fabric.impl"));

        String renderer = Files.readString(clientSource("item/BlendLibItemSpecialRenderer.java"));
        String submitBody = renderer.substring(
                renderer.indexOf("public void submit("), renderer.indexOf("public void getExtents("));
        for (String forbidden : List.of(
                "models().resolve", "ResourceManager", "ModelAssetLoader", "GlbReader", "StrictJsonParser",
                "java.nio.file", "Minecraft.getInstance", ".parse(", "hasFoil ?", "outlineColor ?")) {
            assertFalse(submitBody.contains(forbidden), forbidden);
        }
        assertTrue(renderer.contains("extractArgument(ItemStack stack)"));
        assertTrue(renderer.contains("BlendLibClientServices.models().resolve(binding.modelKey())"));
    }

    private static Path clientSource(String relativePath) {
        return Path.of(System.getProperty("blendlib.projectDir"), "src", "client", "java", "com", "liy", "blendlib", "fabric", "client")
                .resolve(relativePath);
    }

    private static final class TestUnbaked implements ItemModel.Unbaked {
        @Override
        public void resolveDependencies(ResolvableModel.Resolver resolver) {
            // The before-bake adapter must not resolve a replacement's dependencies itself.
        }

        @Override
        public ItemModel bake(ItemModel.BakingContext context, Matrix4fc transformation) {
            throw new AssertionError("The marker selection test must not bake the incoming model");
        }

        @Override
        public MapCodec<? extends ItemModel.Unbaked> type() {
            return MapCodec.unit(this);
        }
    }

    private record TestBeforeBakeItemContext(Identifier itemId) implements ModelModifier.BeforeBakeItem.Context {
        @Override
        public ItemModel.BakingContext bakingContext() {
            return null;
        }

        @Override
        public Matrix4fc transformation() {
            return new Matrix4f();
        }
    }
}
