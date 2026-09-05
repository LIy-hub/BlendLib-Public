package com.liy.blendlib.fabric.v262.client;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.descriptor.MaterialDefinition;
import com.liy.blendlib.core.model.MeshPrimitive;
import com.liy.blendlib.core.model.ModelAsset;
import com.liy.blendlib.core.model.ModelPrimitive;
import com.liy.blendlib.core.model.ModelProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

/**
 * Immutable, reload-time static fixture handle for the standalone Minecraft 26.2 adapter spike.
 *
 * <p>The handle copies only decoded v1 rigid geometry. It intentionally has no registry, resource manager,
 * animation controller, GLB parser, filesystem access, or raw OpenGL dependency. Submit accepts a prebuilt
 * immutable handle and performs only collector/vertex submission through public 26.2 Blaze3D APIs.</p>
 */
public final class V262StaticPreparedHandle {
    private static final int FULL_BRIGHT_PACKED_LIGHT = 0x00F000F0;

    private final BlendResourceId modelKey;
    private final long generation;
    private final List<PreparedPrimitive> primitives;

    private V262StaticPreparedHandle(BlendResourceId modelKey, long generation, List<PreparedPrimitive> primitives) {
        this.modelKey = Objects.requireNonNull(modelKey, "modelKey");
        if (generation < 0L) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
        this.generation = generation;
        this.primitives = List.copyOf(primitives);
        if (this.primitives.isEmpty()) {
            throw new IllegalArgumentException("The v262 static showcase requires at least one primitive");
        }
    }

    /** Prepares a defensive-copy static handle from the unchanged core v1 model asset. */
    public static V262StaticPreparedHandle prepare(ModelAsset asset) {
        ModelAsset checkedAsset = Objects.requireNonNull(asset, "asset");
        if (checkedAsset.profile() != ModelProfile.RIGID_V1 || checkedAsset.skeleton() != null) {
            throw new IllegalArgumentException("The v262 spike only accepts decoded rigid_v1 assets without skins");
        }
        List<PreparedPrimitive> prepared = new ArrayList<>();
        for (ModelPrimitive primitive : checkedAsset.primitives()) {
            MeshPrimitive geometry = primitive.geometry();
            MaterialDefinition material = checkedAsset.materials().get(geometry.materialSlot());
            if (material == null) {
                throw new IllegalArgumentException("A static primitive has no descriptor material: " + geometry.materialSlot());
            }
            if (material.mode() != MaterialDefinition.Mode.OPAQUE || material.doubleSided() || material.emissive()) {
                throw new IllegalArgumentException("The minimal v262 static spike accepts only non-emissive single-sided opaque material");
            }
            prepared.add(new PreparedPrimitive(
                    Identifier.fromNamespaceAndPath(material.baseColor().namespace(), material.baseColor().path()),
                    geometry.positions(),
                    geometry.normals(),
                    geometry.texCoords(),
                    geometry.indices()));
        }
        return new V262StaticPreparedHandle(checkedAsset.modelKey(), checkedAsset.generation(), prepared);
    }

    public BlendResourceId modelKey() {
        return modelKey;
    }

    public long generation() {
        return generation;
    }

    public int primitiveCount() {
        return primitives.size();
    }

    /**
     * Submits immutable fixture geometry through the public 26.2 collector API.
     *
     * <p>This production-shaped overload selects the standard public entity-solid RenderType. The package-private
     * resolver overload exists only so the unit test can execute the callback without bootstrapping a real renderer.</p>
     */
    public void submit(PoseStack poseStack, SubmitNodeCollector collector) {
        submit(poseStack, collector, V262StaticPreparedHandle::entitySolid);
    }

    void submit(PoseStack poseStack, SubmitNodeCollector collector, RenderTypeResolver renderTypeResolver) {
        PoseStack checkedPoseStack = Objects.requireNonNull(poseStack, "poseStack");
        SubmitNodeCollector checkedCollector = Objects.requireNonNull(collector, "collector");
        RenderTypeResolver checkedResolver = Objects.requireNonNull(renderTypeResolver, "renderTypeResolver");
        for (PreparedPrimitive primitive : primitives) {
            RenderType renderType = Objects.requireNonNull(checkedResolver.resolve(primitive.texture), "renderTypeResolver result");
            checkedCollector.submitCustomGeometry(
                    checkedPoseStack,
                    renderType,
                    (pose, consumer) -> primitive.emit(pose, consumer));
        }
    }

    private static RenderType entitySolid(Identifier texture) {
        return RenderTypes.entitySolid(texture);
    }

    @FunctionalInterface
    interface RenderTypeResolver {
        RenderType resolve(Identifier texture);
    }

    /** Private immutable copied vertex/index payload; no source geometry arrays survive handle preparation. */
    private static final class PreparedPrimitive {
        private final Identifier texture;
        private final float[] positions;
        private final float[] normals;
        private final float[] texCoords;
        private final int[] indices;

        private PreparedPrimitive(Identifier texture, float[] positions, float[] normals, float[] texCoords, int[] indices) {
            this.texture = Objects.requireNonNull(texture, "texture");
            this.positions = copyFinite(positions, "positions");
            this.normals = copyFinite(normals, "normals");
            this.texCoords = copyFinite(texCoords, "texCoords");
            this.indices = Arrays.copyOf(Objects.requireNonNull(indices, "indices"), indices.length);
            if (this.positions.length == 0 || this.positions.length % 3 != 0 || this.normals.length != this.positions.length
                    || this.texCoords.length != vertexCount() * 2 || this.indices.length == 0 || this.indices.length % 3 != 0) {
                throw new IllegalArgumentException("Prepared v262 static geometry violates the strict v1 triangle layout");
            }
            for (int index : this.indices) {
                if (index < 0 || index >= vertexCount()) {
                    throw new IllegalArgumentException("Prepared v262 static geometry has an out-of-range index");
                }
            }
        }

        private int vertexCount() {
            return positions.length / 3;
        }

        private void emit(PoseStack.Pose pose, VertexConsumer consumer) {
            for (int index : indices) {
                int positionOffset = index * 3;
                int textureOffset = index * 2;
                consumer.addVertex(pose, positions[positionOffset], positions[positionOffset + 1], positions[positionOffset + 2])
                        .setColor(0xFFFFFFFF)
                        .setUv(texCoords[textureOffset], texCoords[textureOffset + 1])
                        .setOverlay(0)
                        .setLight(FULL_BRIGHT_PACKED_LIGHT)
                        .setNormal(pose, normals[positionOffset], normals[positionOffset + 1], normals[positionOffset + 2]);
            }
        }

        private static float[] copyFinite(float[] source, String name) {
            float[] copy = Arrays.copyOf(Objects.requireNonNull(source, name), source.length);
            for (float value : copy) {
                if (!Float.isFinite(value)) {
                    throw new IllegalArgumentException(name + " must contain finite values");
                }
            }
            return copy;
        }
    }
}
