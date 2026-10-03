package com.liy.blendlib.fabric.client.reload;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import com.liy.blendlib.fabric.client.render.RenderVisibility;
import com.liy.blendlib.fabric.client.render.CullingMetadata;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.descriptor.MaterialDefinition;
import com.liy.blendlib.core.diagnostic.BlendDiagnostic;
import com.liy.blendlib.core.diagnostic.BlendDiagnosticCodes;
import com.liy.blendlib.core.model.Bounds;
import com.liy.blendlib.core.model.MeshPrimitive;
import com.liy.blendlib.core.model.ModelAsset;
import com.liy.blendlib.core.model.ModelNode;
import com.liy.blendlib.core.model.ModelPrimitive;
import com.liy.blendlib.core.model.ModelProfile;
import com.liy.blendlib.core.model.SocketTable;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import com.liy.blendlib.fabric.client.render.MaterialRejectionReason;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NamedSkinReloadTest {
    private static final BlendModelKey MODEL_KEY = BlendModelKey.parse("skin_test:model");
    private static final BlendResourceId TEXTURE = BlendResourceId.parse("skin_test:textures/base.png");
    private static final BlendResourceId GOOD = BlendResourceId.parse("skin_test:good");
    private static final BlendResourceId BAD_SLOT = BlendResourceId.parse("skin_test:bad_slot");
    private static final BlendResourceId BAD_TEXTURE = BlendResourceId.parse("skin_test:bad_texture");
    private static final BlendResourceId NEW_TEXTURE = BlendResourceId.parse("skin_test:textures/new.png");
    private static final String MATERIAL_SLOT = "Material~Slot/One";
    private static final long GENERATION = 41L;

    @Test
    void invalidDefinitionsAreAtomicAndDoNotPoisonValidSkinOrBaseModel() {
        ModelAsset asset = asset(material(MaterialDefinition.Mode.CUTOUT, true, true, null));
        var definitions = new LinkedHashMap<BlendResourceId, Map<String, BlendResourceId>>();
        definitions.put(GOOD, Map.of(MATERIAL_SLOT, NEW_TEXTURE));
        definitions.put(BAD_SLOT, Map.of(MATERIAL_SLOT, NEW_TEXTURE, "missing", NEW_TEXTURE));
        definitions.put(BAD_TEXTURE, Map.of(MATERIAL_SLOT, BlendResourceId.parse("skin_test:textures/missing.png")));
        var diagnostics = new ArrayList<BlendDiagnostic>();
        var resources = new Textures(Set.of(NEW_TEXTURE.value()));
        PreparedNamedSkins skins = ClientModelReloadListener.validateNamedSkins(resources.manager(), MODEL_KEY, asset, definitions, diagnostics);
        assertEquals(Set.of(GOOD), skins.valid().keySet());
        assertEquals(Set.of(BAD_SLOT, BAD_TEXTURE), skins.invalid().keySet());
        assertEquals(2, diagnostics.size());
        assertTrue(diagnostics.stream().allMatch(d -> d.code().equals("SKIN_001")));
        assertTrue(skins.invalid().get(BAD_SLOT).contains("missing"));
        assertTrue(skins.invalid().get(BAD_TEXTURE).contains("Missing texture"));
        definitions.clear();
        var prepared = new PreparedModelGeneration(GENERATION, Map.of(MODEL_KEY, asset), Map.of(), diagnostics,
                Map.of(MODEL_KEY, skins));
        var published = ClientModelReloadListener.createPublishedGeneration(prepared);
        var handle = published.find(MODEL_KEY).orElseThrow().renderHandle();
        assertFalse(handle.missingModel());
        assertTrue(published.primaryDiagnostic(MODEL_KEY).isEmpty());
        assertEquals(NEW_TEXTURE, handle.namedSkins().materials().get(GOOD).getFirst().textureId());
        assertEquals(TEXTURE, handle.primitives().getFirst().material().textureId());
        assertEquals(skins.invalid(), handle.namedSkins().diagnostics());
        assertEquals(0, resources.opens.get(), "existence checks must not pretend to decode textures");
        assertThrows(UnsupportedOperationException.class, () -> skins.valid().clear());
        assertThrows(UnsupportedOperationException.class, () -> skins.valid().get(GOOD).clear());
    }

    @Test
    void eachReloadRevalidatesResourcesWithoutMutatingEarlierCatalog() {
        ModelAsset asset = asset(material(MaterialDefinition.Mode.OPAQUE, false, false, null));
        var definitions = Map.of(GOOD, Map.of(MATERIAL_SLOT, NEW_TEXTURE));
        var first = ClientModelReloadListener.validateNamedSkins(new Textures(Set.of(NEW_TEXTURE.value())).manager(), MODEL_KEY,
                asset, definitions, new ArrayList<>());
        var second = ClientModelReloadListener.validateNamedSkins(new Textures(Set.of()).manager(), MODEL_KEY,
                asset, definitions, new ArrayList<>());
        assertTrue(first.invalid().isEmpty());
        assertTrue(second.valid().isEmpty());
        assertTrue(second.invalid().containsKey(GOOD));
        assertEquals(NEW_TEXTURE, first.valid().get(GOOD).get(MATERIAL_SLOT));
    }

    @Test
    void legacyPreparedGenerationCarriesNoSkinState() {
        var prepared = new PreparedModelGeneration(GENERATION, Map.of(), Map.of(), List.of());
        assertTrue(prepared.namedSkins(MODEL_KEY).valid().isEmpty());
        assertTrue(prepared.namedSkins(MODEL_KEY).invalid().isEmpty());
    }

    private static final class Textures {
        private final Set<String> existing;
        private final AtomicInteger opens = new AtomicInteger();
        Textures(Set<String> existing) { this.existing = existing; }
        public Optional<Resource> getResource(Identifier id) {
            return existing.contains(id.toString()) ? Optional.of(new Resource(null, () -> {
                opens.incrementAndGet();
                throw new AssertionError("Texture must not be decoded here");
            })) : Optional.empty();
        }
        ResourceManager manager() {
            return (ResourceManager) java.lang.reflect.Proxy.newProxyInstance(ResourceManager.class.getClassLoader(),
                    new Class<?>[] { ResourceManager.class }, (proxy, method, args) -> {
                        if (method.getName().equals("getResource")) return getResource((Identifier) args[0]);
                        throw new AssertionError("Unexpected resource operation: " + method.getName());
                    });
        }
    }
    private static MaterialDefinition material(
            MaterialDefinition.Mode mode, boolean emissive, boolean doubleSided, Double cutoutThreshold) {
        return new MaterialDefinition(TEXTURE, mode, emissive, doubleSided, cutoutThreshold);
    }

    private static ModelAsset asset(MaterialDefinition material) {
        MeshPrimitive primitive = new MeshPrimitive(
                MATERIAL_SLOT,
                new float[] {0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F},
                new float[] {0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F},
                new float[] {0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F},
                new int[] {0, 1, 2},
                null,
                null);
        return new ModelAsset(
                MODEL_KEY.resourceId(),
                MODEL_KEY.descriptorResourceId(),
                GENERATION,
                ModelProfile.RIGID_V1,
                1.0D,
                Map.of(MATERIAL_SLOT, material),
                null,
                List.of(new ModelNode(0, "Root", Transform.IDENTITY, List.of(), 0, -1, false)),
                List.of(0),
                List.of(new ModelPrimitive(0, 0, 0, primitive)),
                null,
                List.of(),
                new SocketTable(Map.of()),
                new Bounds(Vec3.ZERO, new Vec3(1.0F, 1.0F, 0.0F)),
                List.of());
    }

}
