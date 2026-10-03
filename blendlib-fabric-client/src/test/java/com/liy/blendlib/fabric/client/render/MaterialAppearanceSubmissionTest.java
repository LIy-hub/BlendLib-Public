package com.liy.blendlib.fabric.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.runtime.CpuSkinner;
import com.liy.blendlib.core.animation.runtime.LocalPose;
import com.liy.blendlib.core.animation.runtime.NodePalette;
import com.liy.blendlib.core.animation.runtime.SkinPalette;
import com.liy.blendlib.core.descriptor.MaterialDefinition;
import com.liy.blendlib.core.model.Bounds;
import com.liy.blendlib.core.model.MeshPrimitive;
import com.liy.blendlib.core.model.ModelAsset;
import com.liy.blendlib.core.model.ModelNode;
import com.liy.blendlib.core.model.ModelPrimitive;
import com.liy.blendlib.core.model.ModelProfile;
import com.liy.blendlib.core.model.Skeleton;
import com.liy.blendlib.core.model.Skin;
import com.liy.blendlib.core.model.SocketTable;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

/** Executes actual backend submission callbacks, including every emitted vertex attribute. */
class MaterialAppearanceSubmissionTest {
    private static final BlendModelKey KEY = BlendModelKey.parse("appearance_test:actor");
    private static final int LIGHT = 0x000A000B;
    private static final int OVERLAY = 0x00030004;
    private static final int WHOLE_TINT = 0xC080C0FF;
    private static final int AUTHORED_TINT = 0x80C08040;
    private static final int BASE_COLOR = 0x60606040;
    private static final int SLOT_COLOR = 0x60301840;

    @Test
    void staticSubmissionMultipliesRgbRetainsAlphaAndOmitsOnlySelectedPrimitives() {
        assertAppearanceSubmission(fixture(false, false));
    }

    @Test
    void animatedRigidSubmissionRetainsCapturedPaletteAndAllOtherDrawAttributes() {
        assertAppearanceSubmission(fixture(false, true));
    }

    @Test
    void capturedSkinnedSubmissionUsesPrimitiveOrdinalIncludingAfterHiddenPrimitives() {
        assertAppearanceSubmission(fixture(true, false));
    }

    @Test
    void allHiddenAndWholeModelCulledSubmitNoGeometryAndRestorePoseStack() {
        for (boolean skinned : List.of(false, true)) {
            ModelRenderSnapshot original = fixture(skinned, false);
            assertTrue(submit(original.withMaterialAppearance(Map.of(
                    "Helmet", new MaterialSlotAppearance(0xFFFFFF, false),
                    "Body", new MaterialSlotAppearance(0xFFFFFF, false),
                    "Boots", new MaterialSlotAppearance(0xFFFFFF, false)))).isEmpty());
            ModelRenderSnapshot culled = skinned
                    ? ModelRenderSnapshot.skinned((SkinnedRenderHandle) original.handle(), original.rootTransform(),
                            LIGHT, OVERLAY, WHOLE_TINT, RenderVisibility.CULLED, original.culling(),
                            original.skinnedRenderSnapshot())
                    : new ModelRenderSnapshot(original.handle(), original.rootTransform(), LIGHT, OVERLAY,
                            WHOLE_TINT, RenderVisibility.CULLED, original.culling());
            assertTrue(submit(culled.withMaterialAppearance(Map.of(
                    "Helmet", new MaterialSlotAppearance(0x123456, true)))).isEmpty());
        }
    }

    @Test
    void missingModelDiagnosticsIgnoreAppearanceSelection() {
        MissingModelRenderHandle handle = new MissingModelRenderHandle(KEY, 8L);
        ModelRenderSnapshot snapshot = new ModelRenderSnapshot(handle, Transform.IDENTITY, LIGHT, OVERLAY,
                WHOLE_TINT, RenderVisibility.VISIBLE, new CullingMetadata(handle.bounds(), true));
        assertEquals(submit(snapshot), submit(snapshot.withMaterialAppearance(
                Map.of("Helmet", new MaterialSlotAppearance(0, false)))));
    }

    @Test
    void namedSkinsComposeWithRgbVisibilityAcrossStaticRigidAndSkinnedSubmission() {
        for (var modes : List.of(new boolean[]{false, false}, new boolean[]{false, true}, new boolean[]{true, false})) {
            var base = fixture(modes[0], modes[1]);
            var skin = BlendResourceId.parse("appearance_test:winter");
            var selected = base.withSkin(java.util.Optional.of(skin)).withMaterialAppearance(Map.of(
                    "Helmet", new MaterialSlotAppearance(0x8040FF, true),
                    "Body", new MaterialSlotAppearance(0xffffff, false)));
            var actual = submit(selected);
            var baseline = submit(base);
            assertEquals(3, actual.size());
            var expectedType = new Minecraft2612StaticRigidRenderBackend().renderTypeFor(
                    base.handle().namedSkins().materials().get(skin).getFirst());
            assertEquals(expectedType, actual.get(0).type());
            assertEquals(expectedType, actual.get(1).type());
            assertEquals(baseline.get(0).vertices().stream().map(v -> v.withColor(SLOT_COLOR)).toList(), actual.get(0).vertices());
            assertEquals(baseline.get(2).vertices().stream().map(v -> v.withColor(SLOT_COLOR)).toList(), actual.get(1).vertices());
            assertEquals(baseline.get(3), actual.get(2));
            assertSame(base.skinnedRenderSnapshot(), selected.skinnedRenderSnapshot());
            assertSame(base.rigidNodePalette(), selected.rigidNodePalette());
            assertEquals(baseline, submit(base.withSkin(java.util.Optional.of(BlendResourceId.parse("appearance_test:unknown")))));
        }
    }

    private static void assertAppearanceSubmission(ModelRenderSnapshot original) {
        List<Draw> baseline = submit(original);
        assertEquals(4, baseline.size());
        for (Draw draw : baseline) {
            assertEquals(4, draw.vertices().size(), "triangle is emitted through the existing quad pipeline");
            for (Vertex vertex : draw.vertices()) {
                assertEquals(BASE_COLOR, vertex.color());
                assertEquals(Minecraft2612StaticRigidRenderBackend.FULL_BRIGHT_PACKED_LIGHT, vertex.light());
                assertEquals(OVERLAY, vertex.overlay());
            }
        }
        ModelRenderSnapshot selected = original.withMaterialAppearance(Map.of(
                "Helmet", new MaterialSlotAppearance(0x8040FF, true),
                "Body", new MaterialSlotAppearance(0xFFFFFF, false)));
        assertSame(original.handle(), selected.handle());
        assertSame(original.skinnedRenderSnapshot(), selected.skinnedRenderSnapshot());
        assertSame(original.rigidNodePalette(), selected.rigidNodePalette());
        assertEquals(original.culling(), selected.culling());
        List<Draw> actual = submit(selected);
        assertEquals(3, actual.size());
        assertDrawExceptColor(baseline.get(0), actual.get(0), SLOT_COLOR);
        assertDrawExceptColor(baseline.get(2), actual.get(1), SLOT_COLOR);
        assertEquals(baseline.get(3), actual.get(2), "omitted slot stays completely unchanged");
        List<Draw> hiddenFirst = submit(original.withMaterialAppearance(Map.of(
                "Helmet", new MaterialSlotAppearance(0xFFFFFF, false),
                "Body", new MaterialSlotAppearance(0x8040FF, true))));
        assertEquals(2, hiddenFirst.size());
        assertDrawExceptColor(baseline.get(1), hiddenFirst.get(0), SLOT_COLOR);
        assertEquals(baseline.get(3), hiddenFirst.get(1));
        assertEquals(baseline, submit(original), "other entities sharing the handle keep their original appearance");
        assertEquals(baseline, submit(original.withMaterialAppearance(Map.of())));
        assertEquals(baseline, submit(original.withMaterialAppearance(Map.of("Helmet", MaterialSlotAppearance.unchanged()))));
    }

    private static void assertDrawExceptColor(Draw expected, Draw actual, int color) {
        assertEquals(expected.type(), actual.type());
        assertEquals(expected.pose(), actual.pose());
        assertEquals(expected.normal(), actual.normal());
        assertEquals(expected.vertices().stream().map(vertex -> vertex.withColor(color)).toList(), actual.vertices());
        assertEquals(BASE_COLOR >>> 24, color >>> 24, "slot RGB must preserve authored/whole-model alpha");
    }

    private static ModelRenderSnapshot fixture(boolean skinned, boolean animated) {
        List<ModelNode> nodes = List.of(
                new ModelNode(0, "Mesh", translation(2, 4, 6), List.of(1), 0, skinned ? 0 : -1, false),
                new ModelNode(1, "Bone", Transform.IDENTITY, List.of(), -1, -1, false));
        List<ModelPrimitive> primitives = new ArrayList<>();
        for (String slot : List.of("Helmet", "Body", "Helmet", "Boots")) {
            primitives.add(new ModelPrimitive(0, 0, primitives.size(), geometry(slot, skinned)));
        }
        Skin skin = new Skin("Skin", 1, List.of(1), new float[] {
            1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1
        });
        MaterialDefinition definition = new MaterialDefinition(
                BlendResourceId.parse("appearance_test:textures/actor.png"),
                MaterialDefinition.Mode.CUTOUT, true, false, null);
        ModelAsset asset = new ModelAsset(KEY.resourceId(), KEY.descriptorResourceId(), 8L,
                skinned ? ModelProfile.SKINNED_V1 : ModelProfile.RIGID_V1, 2.0,
                Map.of("Helmet", definition, "Body", definition, "Boots", definition), null,
                nodes, List.of(0), primitives, skinned ? new Skeleton(List.of(skin)) : null,
                List.of(), new SocketTable(Map.of()), Bounds.fromPositions(primitives.getFirst().geometry().positions()),
                List.of());
        MaterialRenderMapper.MaterialResolver resolver = ignored -> new MaterialMapping.Supported(
                new RenderMaterial(definition.baseColor(), RenderLayer.CUTOUT, true, false, AUTHORED_TINT, false));
        Transform root = translation(5, 6, 7);
        if (skinned) {
            SkinnedRenderHandle handle = SkinnedRenderHandle.prepareWithSkins(KEY, asset, resolver, namedSkins(), Map.of());
            SkinPalette palette = SkinPalette.from(skin, NodePalette.from(
                    new LocalPose(Map.of(0, translation(3, 5, 7), 1, translation(0, 1, 0))), nodes));
            SkinnedRenderSnapshot capture = SkinnedRenderSnapshot.capture(handle, handle.skinnedPrimitives().stream()
                    .map(primitive -> CpuSkinner.skin(primitive.geometry(), palette)).toList());
            return ModelRenderSnapshot.skinned(handle, root, LIGHT, OVERLAY, WHOLE_TINT,
                    RenderVisibility.VISIBLE, new CullingMetadata(handle.bounds(), true), capture);
        }
        StaticRigidRenderHandle handle = StaticRigidRenderHandle.prepareWithSkins(KEY, asset, resolver, namedSkins(), Map.of());
        CullingMetadata culling = new CullingMetadata(handle.bounds(), true);
        return animated
                ? ModelRenderSnapshot.rigid(handle, root, LIGHT, OVERLAY, WHOLE_TINT, RenderVisibility.VISIBLE,
                        culling, Map.of(0, translation(9, 8, 7)))
                : new ModelRenderSnapshot(handle, root, LIGHT, OVERLAY, WHOLE_TINT, RenderVisibility.VISIBLE, culling);
    }

    private static Map<BlendResourceId, Map<String, BlendResourceId>> namedSkins() {
        return Map.of(BlendResourceId.parse("appearance_test:winter"),
                Map.of("Helmet", BlendResourceId.parse("appearance_test:textures/winter.png")));
    }

    private static MeshPrimitive geometry(String slot, boolean skinned) {
        return new MeshPrimitive(slot,
                new float[] {1, 0, 0, 0, 1, 0, 0, 0, 1},
                new float[] {0, 0, 1, 0, 0, 1, 0, 0, 1},
                new float[] {0.1F, 0.2F, 0.3F, 0.4F, 0.5F, 0.6F}, new int[] {2, 0, 1},
                skinned ? new int[12] : null,
                skinned ? new float[] {1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0} : null);
    }

    private static Transform translation(float x, float y, float z) {
        return new Transform(new Vec3(x, y, z), Transform.IDENTITY.rotation(), Vec3.ONE);
    }

    private static List<Draw> submit(ModelRenderSnapshot snapshot) {
        PoseStack stack = new PoseStack();
        stack.translate(11, 12, 13);
        Matrix4f before = new Matrix4f(stack.last().pose());
        CapturingStorage storage = new CapturingStorage();
        new Minecraft2612StaticRigidRenderBackend().submit(snapshot, new RenderSubmissionContext(stack, storage));
        assertEquals(before, stack.last().pose(), "submit restores the caller's pose stack");
        return List.copyOf(storage.draws);
    }

    private record Draw(RenderType type, Matrix4f pose, Matrix3f normal, List<Vertex> vertices) {}

    private record Vertex(float x, float y, float z, int color, float u, float v,
            int overlay, int light, float nx, float ny, float nz) {
        Vertex withColor(int replacement) {
            return new Vertex(x, y, z, replacement, u, v, overlay, light, nx, ny, nz);
        }
    }

    private static final class CapturingStorage extends SubmitNodeStorage {
        private final List<Draw> draws = new ArrayList<>();

        @Override
        public void submitCustomGeometry(PoseStack stack, RenderType type,
                SubmitNodeCollector.CustomGeometryRenderer renderer) {
            CapturingConsumer consumer = new CapturingConsumer();
            renderer.render(stack.last(), consumer);
            draws.add(new Draw(type, new Matrix4f(stack.last().pose()), new Matrix3f(stack.last().normal()),
                    List.copyOf(consumer.vertices)));
        }
    }

    private static final class CapturingConsumer implements VertexConsumer {
        private final List<Vertex> vertices = new ArrayList<>();
        private float x, y, z, u, v;
        private int color, overlay, light;

        @Override public VertexConsumer addVertex(float x, float y, float z) {
            this.x = x; this.y = y; this.z = z; return this;
        }
        @Override public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            return setColor(alpha << 24 | red << 16 | green << 8 | blue);
        }
        @Override public VertexConsumer setColor(int argb) { color = argb; return this; }
        @Override public VertexConsumer setUv(float u, float v) { this.u = u; this.v = v; return this; }
        @Override public VertexConsumer setUv1(int u, int v) { overlay = (u & 0xFFFF) | v << 16; return this; }
        @Override public VertexConsumer setUv2(int u, int v) { light = (u & 0xFFFF) | v << 16; return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) {
            vertices.add(new Vertex(this.x, this.y, this.z, color, u, v, overlay, light, x, y, z)); return this;
        }
        @Override public VertexConsumer setLineWidth(float width) { return this; }
        // Added by the official 26.3 vertex API; kept without @Override for the root 26.1.2 tests.
        public VertexConsumer setUv3(float u, float v) { return this; }
    }
}
