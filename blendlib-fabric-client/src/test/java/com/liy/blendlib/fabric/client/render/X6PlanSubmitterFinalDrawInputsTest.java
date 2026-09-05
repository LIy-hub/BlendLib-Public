package com.liy.blendlib.fabric.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.model.Bounds;
import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

/** Focused H1 parity: the Stage-A value is copied from exactly the same final CPU draw state. */
class X6PlanSubmitterFinalDrawInputsTest {
    private static final BlendModelKey KEY = BlendModelKey.parse("x7_t3c_final_draw:actor/base");
    private static final BlendResourceId PART = BlendResourceId.parse("x7_t3c_final_draw:part/body");
    private static final long GENERATION = 704L;

    @Test
    void stageAFreezesTheExactCpuFinalPoseNormalAndEmissiveLight() {
        Fixture fixture = Fixture.create();
        CapturingPort port = new CapturingPort();
        CapturingStorage collector = new CapturingStorage();
        X6PlanSubmitter.installDeferredSubmissionEndpoint(X7DeferredSubmissionEndpoint.forTest(port));
        try {
            X6PlanSubmitter.submit(
                    fixture.plan(),
                    fixture.snapshot(),
                    new RenderSubmissionContext(new PoseStack(), collector),
                    (attachment, context) -> {
                        throw new AssertionError("single static fixture has no attachment work");
                    });

            X6PlanSubmitter.FinalDrawInputs frozen = port.finalInputs;
            assertNotNull(frozen, "the actual endpoint must receive the Stage-A final copy before CPU emission");
            assertEquals(1, collector.submitCount);
            assertEquals(collector.modelView, frozen.modelViewCopy());
            assertEquals(collector.normalTransform, frozen.normalCopy());
            assertEquals(collector.packedLight, frozen.packedLight());
            assertEquals(Minecraft2612StaticRigidRenderBackend.FULL_BRIGHT_PACKED_LIGHT, frozen.packedLight(),
                    "emissive resolution must happen before a future CPU-suppressing admission");
            assertFalse(new Matrix4f().equals(frozen.modelViewCopy()),
                    "unequal root, unit, and node transforms must all affect the frozen final matrix");
        } finally {
            X6PlanSubmitter.installDeferredSubmissionEndpoint(X7DeferredSubmissionEndpoint.current());
            fixture.close();
        }
    }

    private record Fixture(
            X6PreparedRenderPlan plan,
            ModelRenderSnapshot snapshot,
            X6MaterialProviderGeneration providers,
            X6TestLifecycleDrainDispatcher lifecycleOwner) implements AutoCloseable {
        private static Fixture create() {
            RenderMaterial primitiveMaterial = new RenderMaterial(
                    BlendResourceId.parse("x7_t3c_final_draw:textures/emissive.png"),
                    RenderLayer.SOLID,
                    true,
                    false,
                    0xFFFFFFFF,
                    false);
            PreparedRenderPrimitive primitive = new PreparedRenderPrimitive(
                    0,
                    StaticGeometry.of(
                            new float[] {0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F},
                            new float[] {0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F},
                            new float[] {0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F},
                            new int[] {0, 1, 2}),
                    primitiveMaterial);
            ScaledHandle handle = new ScaledHandle(primitive);
            ModelRenderSnapshot snapshot = new ModelRenderSnapshot(
                    handle,
                    new Transform(
                            new Vec3(3.0F, -2.0F, 5.0F),
                            new Quaternion(0.0F, 0.70710677F, 0.0F, 0.70710677F),
                            new Vec3(1.25F, 1.25F, 1.25F)),
                    0x00030002,
                    net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,
                    0xFFFFFFFF,
                    RenderVisibility.VISIBLE,
                    new CullingMetadata(handle.bounds(), true));
            X6PreparedGeometryCatalog geometry = new X6PreparedGeometryCatalog(KEY, GENERATION, Map.of(PART, primitive));
            X6MaterialPlan materials = X6MaterialPlan.prepare(
                    KEY,
                    GENERATION,
                    Map.of(PART, new X6MaterialIntent(
                            primitiveMaterial.textureId(), X6MaterialMode.OPAQUE, true, false, null)))
                    .plan()
                    .orElseThrow();
            X6VariantApplicationPlan variants = new X6VariantApplicationPlan(
                    KEY,
                    GENERATION,
                    List.of(new X6DrawPrimitive(PART, geometry.binding(PART), materials.material(PART), 0xFFFFFFFF)),
                    List.of());
            X6RenderLayerPlan layers = X6RenderLayerPlanner.prepare(KEY, GENERATION, List.of(), geometry)
                    .plan()
                    .orElseThrow();
            X6MaterialProviderGeneration providers = X6MaterialProviderGeneration.prepareAndPublish(
                    KEY, GENERATION, List.of(), List.of());
            X6TestLifecycleDrainDispatcher lifecycleOwner = new X6TestLifecycleDrainDispatcher();
            X6PreparedRenderPlan plan = X6PreparedRenderPlanFactory.prepare(
                    variants, layers, materials, geometry, providers, snapshot, lifecycleOwner).plan().orElseThrow();
            return new Fixture(plan, snapshot, providers, lifecycleOwner);
        }

        @Override
        public void close() {
            plan.close();
            lifecycleOwner.runAll();
            providers.close();
        }
    }

    private static final class ScaledHandle implements ModelRenderHandle {
        private final PreparedRenderPrimitive primitive;
        private final Bounds bounds = Bounds.fromPositions(new float[] {0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F});

        private ScaledHandle(PreparedRenderPrimitive primitive) {
            this.primitive = primitive;
        }

        @Override
        public BlendModelKey modelKey() {
            return KEY;
        }

        @Override
        public long generation() {
            return GENERATION;
        }

        @Override
        public Bounds bounds() {
            return bounds;
        }

        @Override
        public float unitsToBlocksScale() {
            return 0.5F;
        }

        @Override
        public List<PreparedRenderPrimitive> primitives() {
            return List.of(primitive);
        }

        @Override
        public Transform nodeTransform(int nodeIndex) {
            if (nodeIndex != 0) {
                throw new IndexOutOfBoundsException("fixture has exactly one static node");
            }
            return new Transform(
                    new Vec3(-4.0F, 1.0F, 2.0F),
                    new Quaternion(0.70710677F, 0.0F, 0.0F, 0.70710677F),
                    new Vec3(0.75F, 0.75F, 0.75F));
        }

        @Override
        public boolean missingModel() {
            return false;
        }
    }

    private static final class CapturingPort implements X7AfterSolidQueueDriver.OptionalT4Adapter {
        private X6PlanSubmitter.FinalDrawInputs finalInputs;

        @Override
        public boolean provesStageABeforeAfterSolidSeal() {
            return true;
        }

        @Override
        public boolean hasRegisteredT3bPipeline() {
            return true;
        }

        @Override
        public X7AfterSolidQueueDriver.StageAResult tryPreAdmit(
                X6PreparedRenderPlan plan,
                ModelRenderSnapshot snapshot,
                X6DrawPrimitive draw,
                X6PlanSubmitter.FinalDrawInputs inputs) {
            finalInputs = inputs;
            return X7AfterSolidQueueDriver.StageAResult.KEEP_CPU_PRE_ADMISSION_REJECTED;
        }

        @Override
        public X7AfterSolidQueueDriver.StageBResult sealDrainAndExecute(
                com.mojang.blaze3d.textures.GpuTextureView colorTarget,
                com.mojang.blaze3d.textures.GpuTextureView depthTarget) {
            throw new AssertionError("CPU-fallback fixture never reaches Stage B");
        }

        @Override
        public void pollRetainedCompletionFences() {
        }

        @Override
        public void cancelNoCommandForAfterSolidEpoch() {
        }

        @Override
        public void closeAdmissionAndCancelNoCommand() {
        }
    }

    private static final class CapturingStorage extends SubmitNodeStorage {
        private int submitCount;
        private Matrix4f modelView;
        private Matrix3f normalTransform;
        private int packedLight = Integer.MIN_VALUE;

        @Override
        public void submitCustomGeometry(
                PoseStack poseStack, RenderType renderType, SubmitNodeCollector.CustomGeometryRenderer renderer) {
            submitCount++;
            PoseStack.Pose pose = poseStack.last();
            modelView = new Matrix4f(pose.pose());
            normalTransform = new Matrix3f(pose.normal());
            renderer.render(pose, new LightCapturingVertexConsumer(this));
        }
    }

    private static final class LightCapturingVertexConsumer implements VertexConsumer {
        private final CapturingStorage owner;

        private LightCapturingVertexConsumer(CapturingStorage owner) {
            this.owner = owner;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            return this;
        }

        @Override
        public VertexConsumer setColor(int argb) {
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            owner.packedLight = (u & 0xFFFF) | (v & 0xFFFF) << 16;
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            return this;
        }
    }
}
