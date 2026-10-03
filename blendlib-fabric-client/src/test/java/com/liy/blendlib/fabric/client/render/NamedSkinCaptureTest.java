package com.liy.blendlib.fabric.client.render;

import static org.junit.jupiter.api.Assertions.*;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.runtime.*;
import com.liy.blendlib.core.descriptor.MaterialDefinition;
import com.liy.blendlib.core.model.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class NamedSkinCaptureTest {
    private static final BlendModelKey KEY = BlendModelKey.parse("appearance:fixture");
    private static final BlendResourceId SKIN = BlendResourceId.parse("appearance:winter");
    private static final BlendResourceId TEXTURE = BlendResourceId.parse("appearance:textures/winter.png");
    private static final BlendResourceId BAD = BlendResourceId.parse("appearance:bad");

    @Test
    void newPublicCatalogAndRegistryMethodsHaveExactAdditiveAbi() {
        assertEquals(Set.of(
                "register(Lcom/liy/blendlib/api/BlendModelKey;Ljava/util/Map;)V",
                "snapshotForReload()Ljava/util/Map;"), publicMethods(BlendLibModelSkins.class));
        assertEquals(Set.of(
                "empty()Lcom/liy/blendlib/fabric/client/render/NamedSkinCatalog;",
                "materials()Ljava/util/Map;", "diagnostics()Ljava/util/Map;"), publicMethods(NamedSkinCatalog.class));
        assertEquals(0, BlendLibModelSkins.class.getConstructors().length);
        assertEquals(0, NamedSkinCatalog.class.getConstructors().length);
        assertTrue(java.lang.reflect.Modifier.isFinal(NamedSkinCatalog.class.getModifiers()));
        assertTrue(java.lang.reflect.Modifier.isFinal(BlendLibModelSkins.class.getModifiers()));
    }

    private static Set<String> publicMethods(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                .map(method -> method.getName() + java.lang.invoke.MethodType.methodType(
                        method.getReturnType(), method.getParameterTypes()).descriptorString())
                .collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void catalogBindsSharedExactSlotsAndCopiesMutableDefinitionsAndDiagnostics() {
        var replacements = new HashMap<>(Map.of("body", TEXTURE));
        Map<BlendResourceId, Map<String, BlendResourceId>> definitions = new HashMap<>(Map.of(SKIN, replacements));
        var diagnostics = new HashMap<>(Map.of(BAD, "Missing texture"));
        var handle = StaticRigidRenderHandle.prepareWithSkins(KEY, asset(1, false, "body", "eye", "body"), definitions, diagnostics);
        definitions.clear(); replacements.clear(); diagnostics.clear();
        var frame = snapshot(handle).withSkin(Optional.of(SKIN));
        assertEquals(TEXTURE, frame.material(0, handle.primitives().get(0).material()).textureId());
        assertSame(handle.primitives().get(1).material(), frame.material(1, handle.primitives().get(1).material()));
        assertEquals(TEXTURE, frame.material(2, handle.primitives().get(2).material()).textureId());
        assertEquals(Optional.of(SKIN), frame.selectedSkin());
        assertTrue(frame.skinDiagnostic().isEmpty());
        assertEquals("Missing texture", handle.namedSkins().diagnostics().get(BAD));
        assertThrows(UnsupportedOperationException.class, () -> handle.namedSkins().materials().clear());
        assertThrows(UnsupportedOperationException.class, () -> handle.namedSkins().materials().get(SKIN).clear());
        assertThrows(UnsupportedOperationException.class, () -> handle.namedSkins().diagnostics().clear());
    }

    @Test
    void unusedDescriptorSlotsAreValidEvenWhenNoPrimitiveReferencesThem() {
        var asset = asset(1, false, "body");
        var materials = new HashMap<>(asset.materials());
        materials.put("accent", materials.get("body"));
        var withUnusedSlot = new ModelAsset(asset.modelKey(), KEY.descriptorResourceId(), asset.generation(),
                asset.profile(), asset.unitsPerBlock(), materials, null, asset.nodes(), List.of(0),
                asset.primitives(), null, List.of(), new SocketTable(Map.of()), asset.bounds(), List.of());
        var handle = StaticRigidRenderHandle.prepareWithSkins(KEY, withUnusedSlot,
                Map.of(SKIN, Map.of("body", TEXTURE, "accent", TEXTURE)), Map.of());
        var frame = snapshot(handle).withSkin(Optional.of(SKIN));
        assertTrue(frame.skinDiagnostic().isEmpty());
        assertEquals(TEXTURE, frame.material(0, handle.primitives().getFirst().material()).textureId());
    }

    @Test
    void invalidAndUnknownSelectionsFallbackAtomicallyWithoutDisablingValidSibling() {
        var handle = StaticRigidRenderHandle.prepareWithSkins(KEY, asset(1, false, "body", "eye"),
                Map.of(SKIN, Map.of("body", TEXTURE), BAD, Map.of("body", TEXTURE, "Body", TEXTURE)), Map.of());
        var base = snapshot(handle);
        assertEquals(1, handle.namedSkins().materials().size());
        for (var id : List.of(BAD, BlendResourceId.parse("appearance:unknown"))) {
            var frame = base.withSkin(Optional.of(id));
            assertTrue(frame.skinDiagnostic().isPresent());
            assertEquals(Optional.of(id), frame.selectedSkin());
            for (int i = 0; i < 2; i++) assertSame(handle.primitives().get(i).material(), frame.material(i, handle.primitives().get(i).material()));
        }
        var reset = base.withSkin(Optional.of(SKIN)).withSkin(Optional.empty());
        assertTrue(reset.selectedSkin().isEmpty());
        assertSame(handle.primitives().getFirst().material(), reset.material(0, handle.primitives().getFirst().material()));
        var missing = snapshot(new MissingModelRenderHandle(KEY, 1)).withSkin(Optional.of(SKIN));
        assertTrue(missing.selectedSkin().isEmpty());
        assertTrue(missing.skinDiagnostic().isEmpty());
    }

    @Test
    void reloadReordersByNamesAndCapturedSelectionHasExactHandleFence() {
        var first = StaticRigidRenderHandle.prepareWithSkins(KEY, asset(7, false, "body", "eye"), Map.of(SKIN, Map.of("body", TEXTURE)), Map.of());
        var second = StaticRigidRenderHandle.prepareWithSkins(KEY, asset(7, false, "eye", "body"), Map.of(SKIN, Map.of("body", TEXTURE)), Map.of());
        var capture = NamedSkinSnapshot.capture(first, Optional.of(SKIN));
        assertDoesNotThrow(() -> capture.requireCompatible(first));
        assertThrows(IllegalArgumentException.class, () -> capture.requireCompatible(second));
        assertEquals(TEXTURE, snapshot(second).withSkin(Optional.of(SKIN)).material(1, second.primitives().get(1).material()).textureId());
        assertEquals(TEXTURE, capture.material(0, first.primitives().getFirst().material()).textureId());
    }

    @Test
    void allTextureOnlyFlagsSurviveAndCatalogBudgetIsInclusiveWithoutGeometryCeiling() {
        var original = new RenderMaterial(TEXTURE, RenderLayer.CUTOUT, true, true, 0x80402010, true);
        var texture = BlendResourceId.parse("appearance:textures/second.png");
        assertEquals(new RenderMaterial(texture, original.layer(), true, true, 0x80402010, true), TextureOnlyMaterial.replace(original, texture));
        var definitions = Map.of(SKIN, Map.of("body", texture));
        var exact = NamedSkinCatalog.prepare(Collections.nCopies(65_536, original), Collections.nCopies(65_536, "body"), definitions, Map.of());
        assertEquals(65_536, exact.materials().get(SKIN).size());
        var overflow = NamedSkinCatalog.prepare(Collections.nCopies(65_537, original), Collections.nCopies(65_537, "body"), definitions, Map.of());
        assertTrue(overflow.materials().isEmpty());
        assertTrue(overflow.diagnostics().get(SKIN).contains("65"));
        var slots = new String[2049]; Arrays.fill(slots, "body");
        var manyDefinitions = new HashMap<BlendResourceId, Map<String, BlendResourceId>>();
        for (int i = 0; i < 32; i++) manyDefinitions.put(BlendResourceId.parse("appearance:skin" + i), Map.of("body", TEXTURE));
        var handle = StaticRigidRenderHandle.prepareWithSkins(KEY, asset(1, false, slots), manyDefinitions, Map.of());
        assertEquals(2049, handle.primitives().size());
        assertTrue(handle.namedSkins().materials().isEmpty());
        assertEquals(32, handle.namedSkins().diagnostics().size());
        assertEquals(2049, StaticRigidRenderHandle.prepare(KEY, asset(1, false, slots)).primitives().size());
    }

    @Test
    void directPreparationLimitsAndDiagnosticsRemainBoundedAndDeterministic() {
        var original = RenderMaterial.missing(0xffffffff);
        var replacements = new HashMap<String, BlendResourceId>();
        for (int i = 0; i < 65; i++) replacements.put("slot" + i, TEXTURE);
        var result = NamedSkinCatalog.prepare(List.of(original), List.of("body"),
                Map.of(BAD, replacements, SKIN, Map.of("body", TEXTURE)), Map.of());
        assertTrue(result.materials().containsKey(SKIN));
        assertTrue(result.diagnostics().get(BAD).contains("64"));
        var definitions = new HashMap<BlendResourceId, Map<String, BlendResourceId>>();
        for (int i = 0; i < 33; i++) definitions.put(BlendResourceId.parse("appearance:s" + i), Map.of("body", TEXTURE));
        assertEquals(33, NamedSkinCatalog.prepare(List.of(original), List.of("body"), definitions, Map.of()).diagnostics().size());
        var bounded = NamedSkinCatalog.prepare(List.of(original), List.of("body"), Map.of(), Map.of(SKIN, "x".repeat(4000), BAD, "b"));
        assertEquals(1024, bounded.diagnostics().get(SKIN).length());
        assertEquals(List.of(BAD, SKIN), List.copyOf(bounded.diagnostics().keySet()));
    }

    @Test
    void copiesPreserveRigidAndSkinnedGeometrySkinAppearanceSocketAndLighting() {
        for (boolean skinned : List.of(false, true)) {
            var asset = asset(5, skinned, "body");
            ModelRenderSnapshot base;
            if (skinned) {
                var handle = SkinnedRenderHandle.prepareWithSkins(KEY, asset, Map.of(SKIN, Map.of("body", TEXTURE)), Map.of());
                var palette = SkinPalette.from(asset.skeleton().skins().getFirst(), NodePalette.from(
                        new LocalPose(Map.of(0, Transform.IDENTITY, 1, Transform.IDENTITY)), asset.nodes()));
                var output = SkinnedRenderSnapshot.capture(handle, List.of(CpuSkinner.skin(handle.skinnedPrimitives().getFirst().geometry(), palette)));
                base = ModelRenderSnapshot.skinned(handle, Transform.IDENTITY, 12, 34, 0xffabcdef,
                        RenderVisibility.VISIBLE, new CullingMetadata(handle.bounds(), true), output);
            } else {
                var handle = StaticRigidRenderHandle.prepareWithSkins(KEY, asset, Map.of(SKIN, Map.of("body", TEXTURE)), Map.of());
                base = ModelRenderSnapshot.rigid(handle, Transform.IDENTITY, 12, 34, 0xffabcdef,
                        RenderVisibility.VISIBLE, new CullingMetadata(handle.bounds(), true), Map.of(0, Transform.IDENTITY));
            }
            var appearance = Map.of("body", new MaterialSlotAppearance(0x123456, false));
            for (var frame : List.of(base.withSkin(Optional.of(SKIN)).withMaterialAppearance(appearance),
                    base.withMaterialAppearance(appearance).withSkin(Optional.of(SKIN)))) {
                if (skinned) frame = frame.withPresentationSocketTransform(Transform.IDENTITY);
                var copy = frame.withLighting(56, 78).withAttachments(List.of());
                assertSame(base.handle(), copy.handle());
                assertSame(base.rigidNodePalette(), copy.rigidNodePalette());
                assertSame(base.skinnedRenderSnapshot(), copy.skinnedRenderSnapshot());
                assertSame(base.culling(), copy.culling());
                assertEquals(base.tintArgb(), copy.tintArgb());
                assertEquals(56, copy.packedLight()); assertEquals(78, copy.packedOverlay());
                assertEquals(Optional.of(SKIN), copy.selectedSkin());
                assertEquals(appearance.get("body"), copy.materialAppearance(0));
                assertEquals(TEXTURE, copy.material(0, RenderMaterial.missing(0)).textureId());
                if (skinned) assertSame(Transform.IDENTITY, copy.presentationSocketTransformOrNull());
            }
        }
    }

    private static ModelRenderSnapshot snapshot(ModelRenderHandle handle) {
        return new ModelRenderSnapshot(handle, Transform.IDENTITY, 123, 456, 0xffffffff,
                RenderVisibility.VISIBLE, new CullingMetadata(handle.bounds(), true));
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
}
