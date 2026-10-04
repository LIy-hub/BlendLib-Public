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
    private final MorphBindingTable morphBindings;
    private final Map<MeshPrimitive, MorphTargetSet> morphTargets;
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
        this(modelKey, descriptorId, generation, profile, unitsPerBlock, materials, animationDefinition, nodes,
                defaultSceneRoots, primitives, skeleton, clips, sockets, bounds, diagnostics, MorphBindingTable.empty(), Map.of());
    }

    public ModelAsset(BlendResourceId modelKey, BlendResourceId descriptorId, long generation, ModelProfile profile,
            double unitsPerBlock, Map<String, MaterialDefinition> materials, AnimationDefinition animationDefinition,
            List<ModelNode> nodes, List<Integer> defaultSceneRoots, List<ModelPrimitive> primitives, Skeleton skeleton,
            List<AnimationClip> clips, SocketTable sockets, Bounds bounds, List<BlendDiagnostic> diagnostics,
            MorphBindingTable morphBindings, Map<MeshPrimitive, MorphTargetSet> morphTargets) {
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
        this.morphBindings = Objects.requireNonNull(morphBindings, "morphBindings");
        java.util.IdentityHashMap<MeshPrimitive, MorphTargetSet> copiedTargets = new java.util.IdentityHashMap<>();
        copiedTargets.putAll(Objects.requireNonNull(morphTargets, "morphTargets"));
        this.morphTargets = Collections.unmodifiableMap(copiedTargets);
        validateMorphData();
        this.bounds = ConservativeAnimatedBounds.includeAnimations(
                Objects.requireNonNull(bounds, "bounds"),
                this.nodes,
                this.defaultSceneRoots,
                this.primitives,
                this.skeleton,
                this.clips, this.morphBindings, this.morphTargets);
        this.diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    }

    private void validateMorphData() {
        if (profile != ModelProfile.SKINNED_MORPH_CPU_V1) {
            if (!morphBindings.isEmpty() || !morphTargets.isEmpty() || clips.stream().anyMatch(AnimationClip::hasMorphChannels)) {
                throw new IllegalArgumentException("Morph data requires the CPU morph profile");
            }
            return;
        }
        if (morphBindings.isEmpty() || morphTargets.isEmpty()) throw new IllegalArgumentException("CPU morph profile requires morph bindings and targets");
        if (skeleton == null) throw new IllegalArgumentException("CPU morph profile requires a skeleton");
        // The interval proof transforms deltas linearly. A merely near-affine fourth row
        // can change w arbitrarily across a legal large morph, invalidating that proof.
        for (Skin skin : skeleton.skins()) {
            float[] inverseBinds = skin.inverseBindMatrices();
            for (int offset = 0; offset < inverseBinds.length; offset += 16) {
                if (inverseBinds[offset + 3] != 0 || inverseBinds[offset + 7] != 0
                        || inverseBinds[offset + 11] != 0 || inverseBinds[offset + 15] != 1)
                    throw new IllegalArgumentException("CPU morph inverse-bind matrices require exact affine fourth row [0, 0, 0, 1]");
            }
        }
        java.util.Set<MeshPrimitive> used = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        java.util.Set<Integer> boundNodes = new java.util.HashSet<>();
        for (ModelPrimitive p : primitives) {
            MorphTargetSet targets = morphTargets.get(p.geometry());
            MorphBindingTable.Binding binding = morphBindings.binding(p.nodeIndex());
            if ((targets == null) != (binding == null)) throw new IllegalArgumentException("Morph primitive and node binding disagree");
            if (targets != null) {
                if (!p.geometry().skinned() || targets.vertexCount() != p.geometry().vertexCount()
                        || !targets.targetNames().equals(binding.targetNames())) throw new IllegalArgumentException("Morph topology/binding mismatch");
                used.add(p.geometry()); boundNodes.add(p.nodeIndex());
                validateMorphEnvelope(p.geometry(), targets, binding);
            }
        }
        if (used.size() != morphTargets.size() || boundNodes.size() != morphBindings.bindings().size()) throw new IllegalArgumentException("Unbound morph data");
        for (AnimationClip clip : clips) {
            java.util.Set<Integer> targets = new java.util.HashSet<>();
            for (var channel : clip.morphChannels()) {
                MorphBindingTable.Binding binding = morphBindings.binding(channel.targetNode());
                if (binding == null || binding.targetCount() != channel.targetCount() || !targets.add(channel.targetNode())) {
                    throw new IllegalArgumentException("Invalid morph channel binding");
                }
                for (int key = 0; key < channel.keyCount(); key++) for (int t = 0; t < binding.targetCount(); t++) {
                    float value = channel.keyValue(key, t);
                    if (value < binding.minWeight(t) || value > binding.maxWeight(t)) throw new IllegalArgumentException("Morph key outside declared interval");
                }
            }
        }
    }

    /** Positive projection on the base normal proves no interval combination can collapse it. */
    private static void validateMorphEnvelope(MeshPrimitive geometry, MorphTargetSet targets, MorphBindingTable.Binding binding) {
        float[] positions = geometry.positions(), normals = geometry.normals();
        for (int vertex = 0; vertex < geometry.vertexCount(); vertex++) {
            int offset = vertex * 3;
            double norm = Math.hypot(Math.hypot(normals[offset], normals[offset + 1]), normals[offset + 2]);
            if (!(norm > 1.0e-6)) throw new IllegalArgumentException("Morph base normal is not normalizable");
            double minimumProjection = norm, magnitude = norm;
            for (int target = 0; target < targets.targetCount(); target++) {
                double projection = 0, deltaMagnitude = 0;
                for (int c = 0; c < 3; c++) {
                    double delta = targets.normalDelta(target, vertex, c);
                    projection += delta * (normals[offset + c] / norm);
                    deltaMagnitude = Math.hypot(deltaMagnitude, delta);
                }
                minimumProjection += Math.min(binding.minWeight(target) * projection, binding.maxWeight(target) * projection);
                magnitude += Math.max(Math.abs(binding.minWeight(target)), Math.abs(binding.maxWeight(target))) * deltaMagnitude;
            }
            if (!(minimumProjection >= 1.0e-5 + 1.0e-12 * magnitude) || !Double.isFinite(magnitude) || magnitude > Float.MAX_VALUE / 4.0) {
                throw new IllegalArgumentException("Morph interval cannot prove a finite nondegenerate source normal");
            }
            for (int c = 0; c < 3; c++) {
                double bound = Math.abs(positions[offset + c]);
                for (int target = 0; target < targets.targetCount(); target++) bound += Math.max(Math.abs(binding.minWeight(target)),
                        Math.abs(binding.maxWeight(target))) * Math.abs((double) targets.positionDelta(target, vertex, c));
                if (!Double.isFinite(bound) || bound > Float.MAX_VALUE / 4.0) throw new IllegalArgumentException("Morph position interval exceeds finite envelope");
            }
        }
    }

    public MorphBindingTable morphBindings() { return morphBindings; }
    public MorphTargetSet morphTargets(ModelPrimitive primitive) { return morphTargets(primitive.geometry()); }
    public MorphTargetSet morphTargets(MeshPrimitive primitive) { return morphTargets.get(primitive); }

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
