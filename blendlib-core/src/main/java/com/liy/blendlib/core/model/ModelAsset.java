package com.liy.blendlib.core.model;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.AnimationClip;
import com.liy.blendlib.core.descriptor.AnimationDefinition;
import com.liy.blendlib.core.descriptor.MaterialDefinition;
import com.liy.blendlib.core.diagnostic.BlendDiagnostic;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable decoded model resource shared across instances in one generation.
 *
 * <p>It intentionally contains no world, entity, animation-controller, or
 * rendering-state reference.</p>
 */
public final class ModelAsset {
    private final BlendResourceId modelKey;
    private final BlendResourceId descriptorId;
    private final long generation;
    private final ModelProfile profile;
    private final double unitsPerBlock;
    private final Map<String, MaterialDefinition> materials;
    private final AnimationDefinition animationDefinition;
    private final List<ModelNode> nodes;
    private final List<Integer> defaultSceneRoots;
    private final List<ModelPrimitive> primitives;
    private final Skeleton skeleton;
    private final List<AnimationClip> clips;
    private final SocketTable sockets;
    private final Bounds bounds;
    private final List<BlendDiagnostic> diagnostics;

    public ModelAsset(
            BlendResourceId modelKey,
            BlendResourceId descriptorId,
            long generation,
            ModelProfile profile,
            double unitsPerBlock,
            Map<String, MaterialDefinition> materials,
            AnimationDefinition animationDefinition,
            List<ModelNode> nodes,
            List<Integer> defaultSceneRoots,
            List<ModelPrimitive> primitives,
            Skeleton skeleton,
            List<AnimationClip> clips,
            SocketTable sockets,
            Bounds bounds,
            List<BlendDiagnostic> diagnostics) {
        this.modelKey = Objects.requireNonNull(modelKey, "modelKey");
        this.descriptorId = Objects.requireNonNull(descriptorId, "descriptorId");
        if (generation < 0) {
            throw new IllegalArgumentException("Model generation must be non-negative");
        }
        this.generation = generation;
        this.profile = Objects.requireNonNull(profile, "profile");
        if (!Double.isFinite(unitsPerBlock) || unitsPerBlock <= 0.0) {
            throw new IllegalArgumentException("unitsPerBlock must be finite and positive");
        }
        this.unitsPerBlock = unitsPerBlock;
        this.materials = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(materials, "materials")));
        this.animationDefinition = animationDefinition;
        this.nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
        this.defaultSceneRoots = List.copyOf(Objects.requireNonNull(defaultSceneRoots, "defaultSceneRoots"));
        this.primitives = List.copyOf(Objects.requireNonNull(primitives, "primitives"));
        this.skeleton = skeleton;
        this.clips = List.copyOf(Objects.requireNonNull(clips, "clips"));
        this.sockets = Objects.requireNonNull(sockets, "sockets");
        this.bounds = ConservativeAnimatedBounds.includeAnimations(
                Objects.requireNonNull(bounds, "bounds"),
                this.nodes,
                this.defaultSceneRoots,
                this.primitives,
                this.skeleton,
                this.clips);
        this.diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    }

    public BlendResourceId modelKey() {
        return modelKey;
    }

    /** Exact descriptor resource used to create this immutable generation. */
    public BlendResourceId descriptorId() {
        return descriptorId;
    }

    public long generation() {
        return generation;
    }

    public ModelProfile profile() {
        return profile;
    }

    /** Descriptor-declared model units represented by one Minecraft block. */
    public double unitsPerBlock() {
        return unitsPerBlock;
    }

    /** Immutable descriptor material intent keyed by GLB material-slot name. */
    public Map<String, MaterialDefinition> materials() {
        return materials;
    }

    /** Optional immutable descriptor state-to-clip declaration for later instance-controller construction. */
    public AnimationDefinition animationDefinition() {
        return animationDefinition;
    }

    public List<ModelNode> nodes() {
        return nodes;
    }

    /** Exact ordered root-node indices validated from the selected default glTF scene. */
    public List<Integer> defaultSceneRoots() {
        return defaultSceneRoots;
    }

    public List<ModelPrimitive> primitives() {
        return primitives;
    }

    public Skeleton skeleton() {
        return skeleton;
    }

    public List<AnimationClip> clips() {
        return clips;
    }

    public SocketTable sockets() {
        return sockets;
    }

    public Bounds bounds() {
        return bounds;
    }

    public List<BlendDiagnostic> diagnostics() {
        return diagnostics;
    }
}
