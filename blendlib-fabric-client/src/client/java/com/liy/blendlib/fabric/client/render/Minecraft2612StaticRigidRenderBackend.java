package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.Transform;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.Objects;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import org.joml.Quaternionf;

/**
 * Minecraft 26.1.2 static, rigid, and captured CPU-skinned backend using only public collector,
 * RenderType, and VertexConsumer APIs.
 *
 * <p>Without a prepared palette it submits rest-pose transforms. P5 may supply only a prevalidated
 * immutable rigid-node palette or a captured CPU-skinned snapshot; this backend does not sample
 * animation or run CPU skinning. It has no resource lookup, JSON/GLB parsing, skin palette, pose
 * cache, raw OpenGL call, or Minecraft/Fabric implementation-package dependency.</p>
 */
public final class Minecraft2612StaticRigidRenderBackend implements ModelRenderBackend {
    /** Minecraft's public vertex-light packing for block light 15 and sky light 15. */
    static final int FULL_BRIGHT_PACKED_LIGHT = 0x00F000F0;

    @Override
    public void submit(ModelRenderSnapshot snapshot, RenderSubmissionContext context) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(context, "context");
        if (snapshot.visibility() == RenderVisibility.CULLED) {
            return;
        }
        SkinnedRenderSnapshot skinned = snapshot.skinnedRenderSnapshot();
        if (skinned != null) {
            submitCapturedSkinned(snapshot, context, skinned);
            return;
        }
        PoseStack poseStack = context.poseStack();
        SubmitNodeCollector collector = context.collector();
        poseStack.pushPose();
        try {
            applyTransform(poseStack, snapshot.rootTransform());
            float unitsToBlocksScale = snapshot.handle().unitsToBlocksScale();
            poseStack.scale(unitsToBlocksScale, unitsToBlocksScale, unitsToBlocksScale);
            for (PreparedRenderPrimitive primitive : snapshot.handle().primitives()) {
                poseStack.pushPose();
                try {
                    applyTransform(poseStack, nodeTransformFor(snapshot, primitive.nodeIndex()));
                    RenderMaterial material = primitive.material();
                    RenderType renderType = renderTypeFor(material);
                    int color = multiplyArgb(snapshot.tintArgb(), material.argbTint());
                    int packedLight = packedLightFor(material, snapshot.packedLight());
                    collector.submitCustomGeometry(poseStack, renderType,
                            (pose, consumer) -> emit(primitive.geometry(), pose, consumer, color, packedLight, snapshot.packedOverlay()));
                } finally {
                    poseStack.popPose();
                }
            }
        } finally {
            poseStack.popPose();
        }
    }

    /**
     * Submits only extraction-captured CPU-skinned vertex data.
     *
     * <p>Core skinning output is already in the model-space contract established by the prepared
     * skin palette. This public-adapter path therefore applies the normal root transform and unit
     * conversion only; it never samples, skins, parses, or looks up any model state.</p>
     */
    private void submitCapturedSkinned(
            ModelRenderSnapshot snapshot, RenderSubmissionContext context, SkinnedRenderSnapshot skinned) {
        PoseStack poseStack = context.poseStack();
        SubmitNodeCollector collector = context.collector();
        poseStack.pushPose();
        try {
            applyTransform(poseStack, snapshot.rootTransform());
            float unitsToBlocksScale = snapshot.handle().unitsToBlocksScale();
            poseStack.scale(unitsToBlocksScale, unitsToBlocksScale, unitsToBlocksScale);
            for (SkinnedMeshSnapshot mesh : skinned.meshes()) {
                RenderMaterial material = mesh.material();
                RenderType renderType = renderTypeFor(material);
                int color = multiplyArgb(snapshot.tintArgb(), material.argbTint());
                int packedLight = packedLightFor(material, snapshot.packedLight());
                collector.submitCustomGeometry(
                        poseStack,
                        renderType,
                        (pose, consumer) -> emit(mesh, pose, consumer, color, packedLight, snapshot.packedOverlay()));
            }
        } finally {
            poseStack.popPose();
        }
    }

    /** Maps only the exact public P4 material subset; all descriptor rejection happens during handle preparation. */
    public RenderType renderTypeFor(RenderMaterial material) {
        Objects.requireNonNull(material, "material");
        Identifier texture = Identifier.fromNamespaceAndPath(material.textureId().namespace(), material.textureId().path());
        return switch (renderTypePathFor(material)) {
            case ENTITY_SOLID -> RenderTypes.entitySolid(texture);
            case ENTITY_CUTOUT_CULL -> RenderTypes.entityCutoutCull(texture);
            // This boolean controls outline participation, not culling. It is deliberately fixed.
            case ENTITY_CUTOUT -> RenderTypes.entityCutout(texture, false);
            // This boolean controls outline participation, not culling. It is deliberately fixed.
            case ENTITY_TRANSLUCENT -> RenderTypes.entityTranslucent(texture, false);
        };
    }

    /**
     * Pure routing guard for already-prepared materials.
     *
     * <p>Every descriptor-derived material first passes {@link MaterialRenderMapper} during reload. The checks here
     * keep manually constructed adapter material data from silently selecting a culling-incompatible path during
     * submit.</p>
     */
    static RenderTypePath renderTypePathFor(RenderMaterial material) {
        Objects.requireNonNull(material, "material");
        return switch (material.layer()) {
            case SOLID -> {
                if (material.doubleSided()) {
                    throw new IllegalArgumentException("A double-sided solid material has no P4 public render path");
                }
                yield RenderTypePath.ENTITY_SOLID;
            }
            case CUTOUT -> material.doubleSided() ? RenderTypePath.ENTITY_CUTOUT : RenderTypePath.ENTITY_CUTOUT_CULL;
            case TRANSLUCENT -> {
                if (!material.doubleSided()) {
                    throw new IllegalArgumentException("A single-sided translucent material has no P4 public render path");
                }
                yield RenderTypePath.ENTITY_TRANSLUCENT;
            }
        };
    }

    /** Exact standard 26.1.2 public rendering paths selected after reload-time validation. */
    enum RenderTypePath {
        ENTITY_SOLID,
        ENTITY_CUTOUT_CULL,
        ENTITY_CUTOUT,
        ENTITY_TRANSLUCENT
    }

    /** Chooses the public packed vertex light submitted with a prepared material. */
    static int packedLightFor(RenderMaterial material, int defaultPackedLight) {
        Objects.requireNonNull(material, "material");
        return material.emissive() ? FULL_BRIGHT_PACKED_LIGHT : defaultPackedLight;
    }

    /**
     * Selects only a prevalidated palette transform or the existing rest-pose handle transform.
     * No controller, sampling, registry, resource, parser, or file access occurs on submit.
     */
    static Transform nodeTransformFor(ModelRenderSnapshot snapshot, int nodeIndex) {
        ModelRenderSnapshot checkedSnapshot = Objects.requireNonNull(snapshot, "snapshot");
        RigidNodePaletteSnapshot rigidNodePalette = checkedSnapshot.rigidNodePalette();
        return rigidNodePalette == null
                ? checkedSnapshot.handle().nodeTransform(nodeIndex)
                : rigidNodePalette.nodeTransform(nodeIndex);
    }

    private static void applyTransform(PoseStack poseStack, Transform transform) {
        poseStack.translate(transform.translation().x(), transform.translation().y(), transform.translation().z());
        Quaternion rotation = transform.rotation();
        poseStack.mulPose(new Quaternionf(rotation.x(), rotation.y(), rotation.z(), rotation.w()));
        poseStack.scale(transform.scale().x(), transform.scale().y(), transform.scale().z());
    }

    private static void emit(
            StaticGeometry geometry,
            PoseStack.Pose pose,
            VertexConsumer consumer,
            int argb,
            int packedLight,
            int packedOverlay) {
        geometry.emit((x, y, z, normalX, normalY, normalZ, u, v) -> consumer.addVertex(pose, x, y, z)
                .setColor(argb)
                .setUv(u, v)
                .setOverlay(packedOverlay)
                .setLight(packedLight)
                .setNormal(pose, normalX, normalY, normalZ));
    }

    private static void emit(
            SkinnedMeshSnapshot mesh,
            PoseStack.Pose pose,
            VertexConsumer consumer,
            int argb,
            int packedLight,
            int packedOverlay) {
        mesh.emit((x, y, z, normalX, normalY, normalZ, u, v) -> consumer.addVertex(pose, x, y, z)
                .setColor(argb)
                .setUv(u, v)
                .setOverlay(packedOverlay)
                .setLight(packedLight)
                .setNormal(pose, normalX, normalY, normalZ));
    }

    private static int multiplyArgb(int left, int right) {
        int alpha = multiplyChannel(left >>> 24, right >>> 24);
        int red = multiplyChannel(left >>> 16 & 0xFF, right >>> 16 & 0xFF);
        int green = multiplyChannel(left >>> 8 & 0xFF, right >>> 8 & 0xFF);
        int blue = multiplyChannel(left & 0xFF, right & 0xFF);
        return alpha << 24 | red << 16 | green << 8 | blue;
    }

    private static int multiplyChannel(int left, int right) {
        return left * right / 255;
    }
}
