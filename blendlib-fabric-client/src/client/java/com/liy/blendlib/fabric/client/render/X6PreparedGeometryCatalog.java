package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Immutable mapping from canonical X6 part ids to already prepared geometry bindings. */
public final class X6PreparedGeometryCatalog {
    private final BlendModelKey modelKey;
    private final long generation;
    private final Map<BlendResourceId, X6GeometryBinding> bindings;
    private final Map<BlendResourceId, PreparedRenderPrimitive> primitives;
    private final Map<BlendResourceId, X6SkinnedBoneBinding> skinnedBoneBindings;
    private final Set<Integer> retainedSkinnedSkinIndexes;

    public X6PreparedGeometryCatalog(
            BlendModelKey modelKey, long generation, Map<BlendResourceId, PreparedRenderPrimitive> primitives) {
        this(modelKey, generation, primitives, Map.of());
    }

    /**
     * Creates a static/rigid catalog. Legacy node aliases are retained only as input validation;
     * static geometry has no skin joint influence and therefore can never satisfy a per-bone layer.
     */
    public X6PreparedGeometryCatalog(
            BlendModelKey modelKey,
            long generation,
            Map<BlendResourceId, PreparedRenderPrimitive> primitives,
            Map<BlendResourceId, Integer> legacyBoneNodeIndexes) {
        this(modelKey, generation, staticBindings(primitives), validateLegacyStaticBones(legacyBoneNodeIndexes), false);
    }

    /**
     * Creates a skinned catalog whose canonical bones resolve both a skeleton node and a skin-joint
     * index. The latter is frozen so submit never guesses that a primitive node is a bone.
     */
    public static X6PreparedGeometryCatalog skinned(
            BlendModelKey modelKey,
            long generation,
            Map<BlendResourceId, PreparedSkinnedRenderPrimitive> primitives,
            Map<BlendResourceId, Integer> snapshotMeshIndexes,
            Map<BlendResourceId, X6SkinnedBoneBinding> boneBindings) {
        Objects.requireNonNull(primitives, "primitives");
        Objects.requireNonNull(snapshotMeshIndexes, "snapshotMeshIndexes");
        Map<BlendResourceId, X6GeometryBinding> bindings = new LinkedHashMap<>();
        Set<Integer> usedMeshIndexes = new HashSet<>();
        IdentityHashMap<PreparedSkinnedRenderPrimitive, Boolean> usedPrimitives = new IdentityHashMap<>();
        primitives.entrySet().forEach(entry -> {
            Integer meshIndex = snapshotMeshIndexes.get(entry.getKey());
            if (meshIndex == null) {
                throw new IllegalArgumentException("Every skinned X6 part must retain its snapshot mesh index");
            }
            PreparedSkinnedRenderPrimitive primitive = Objects.requireNonNull(entry.getValue(), "prepared skinned primitive");
            if (!usedMeshIndexes.add(meshIndex) || usedPrimitives.put(primitive, Boolean.TRUE) != null) {
                throw new IllegalArgumentException("A skinned X6 catalog may not alias a captured mesh or prepared primitive");
            }
            bindings.put(entry.getKey(), new X6GeometryBinding.SkinnedBinding(meshIndex, primitive));
        });
        if (bindings.size() != snapshotMeshIndexes.size()) {
            throw new IllegalArgumentException("X6 skinned snapshot mesh indexes may not name unknown parts");
        }
        return new X6PreparedGeometryCatalog(modelKey, generation, bindings, boneBindings, true);
    }

    private X6PreparedGeometryCatalog(
            BlendModelKey modelKey,
            long generation,
            Map<BlendResourceId, X6GeometryBinding> sourceBindings,
            Map<BlendResourceId, X6SkinnedBoneBinding> sourceBoneBindings,
            boolean skinned) {
        this.modelKey = Objects.requireNonNull(modelKey, "modelKey");
        if (generation < 0L) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
        Objects.requireNonNull(sourceBindings, "sourceBindings");
        if (sourceBindings.isEmpty() || sourceBindings.size() > X6Ids.MAX_PARTS) {
            throw new IllegalArgumentException("X6 geometry catalog must contain 1.." + X6Ids.MAX_PARTS + " parts");
        }
        Map<BlendResourceId, X6GeometryBinding> orderedBindings = new LinkedHashMap<>();
        Map<BlendResourceId, PreparedRenderPrimitive> orderedPrimitives = new LinkedHashMap<>();
        Set<Integer> orderedSkinnedSkinIndexes = new HashSet<>();
        sourceBindings.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().value()))
                .forEach(entry -> {
                    BlendResourceId partId = X6Ids.requireId(entry.getKey(), "part id");
                    X6GeometryBinding binding = Objects.requireNonNull(entry.getValue(), "prepared geometry binding");
                    if (binding.skinned() != skinned || orderedBindings.put(partId, binding) != null) {
                        throw new IllegalArgumentException("Duplicate X6 geometry part id");
                    }
                    if (binding instanceof X6GeometryBinding.StaticBinding staticBinding) {
                        orderedPrimitives.put(partId, staticBinding.primitive());
                    } else {
                        orderedSkinnedSkinIndexes.add(((X6GeometryBinding.SkinnedBinding) binding).primitive().skinIndex());
                    }
                });
        Objects.requireNonNull(sourceBoneBindings, "sourceBoneBindings");
        Map<BlendResourceId, X6SkinnedBoneBinding> orderedBones = new LinkedHashMap<>();
        sourceBoneBindings.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().value()))
                .forEach(entry -> {
                    BlendResourceId boneId = X6Ids.requireId(entry.getKey(), "bone id");
                    X6SkinnedBoneBinding bone = Objects.requireNonNull(entry.getValue(), "prepared skinned bone binding");
                    if (!skinned) {
                        throw new IllegalArgumentException("Only a skinned X6 catalog may retain joint bindings");
                    }
                    if (orderedBones.put(boneId, bone) != null) {
                        throw new IllegalArgumentException("Duplicate prepared X6 bone id");
                    }
                });
        this.generation = generation;
        this.bindings = X6Ids.immutableOrderedMap(orderedBindings);
        this.primitives = X6Ids.immutableOrderedMap(orderedPrimitives);
        this.skinnedBoneBindings = X6Ids.immutableOrderedMap(orderedBones);
        this.retainedSkinnedSkinIndexes = Set.copyOf(orderedSkinnedSkinIndexes);
    }

    public BlendModelKey modelKey() {
        return modelKey;
    }

    public long generation() {
        return generation;
    }

    public Map<BlendResourceId, PreparedRenderPrimitive> primitives() {
        return primitives;
    }

    /** All catalog identities, including skinned binding positions. */
    public Map<BlendResourceId, X6GeometryBinding> bindings() {
        return bindings;
    }

    public boolean skinned() {
        return bindings.values().iterator().next().skinned();
    }

    /** Returns the frozen canonical skeleton/joint binding when this is a skinned catalog. */
    Optional<X6SkinnedBoneBinding> skinnedBoneBinding(BlendResourceId boneId) {
        return Optional.ofNullable(skinnedBoneBindings.get(Objects.requireNonNull(boneId, "boneId")));
    }

    /**
     * Checks a per-bone selector against its actual prepared skin, never against a primitive node
     * coincidence. Static geometry deliberately returns false.
     */
    public boolean resolvesBoneForPart(BlendResourceId boneId, BlendResourceId partId) {
        X6GeometryBinding binding = bindings.get(Objects.requireNonNull(partId, "partId"));
        X6SkinnedBoneBinding bone = skinnedBoneBindings.get(Objects.requireNonNull(boneId, "boneId"));
        return binding instanceof X6GeometryBinding.SkinnedBinding skinnedBinding
                && bone != null
                && skinnedBinding.primitive().skinIndex() == bone.skinIndex();
    }

    public PreparedRenderPrimitive primitive(BlendResourceId partId) {
        PreparedRenderPrimitive primitive = primitives.get(Objects.requireNonNull(partId, "partId"));
        if (primitive == null) {
            throw new IllegalArgumentException("Unknown prepared X6 geometry part: " + partId);
        }
        return primitive;
    }

    public X6GeometryBinding binding(BlendResourceId partId) {
        X6GeometryBinding binding = bindings.get(Objects.requireNonNull(partId, "partId"));
        if (binding == null) {
            throw new IllegalArgumentException("Unknown prepared X6 geometry part: " + partId);
        }
        return binding;
    }

    /**
     * Validates all catalog identities against the exact snapshot/handle chosen for plan
     * publication. This is intentionally called by the factory, before a collector callback can
     * touch node or mesh indexes.
     */
    int bindForSnapshot(ModelRenderSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        ModelRenderHandle handle = snapshot.handle();
        if (!modelKey.equals(handle.modelKey()) || generation != handle.generation()) {
            throw mismatch("X6 catalog and binding snapshot use different model or generation");
        }
        if (handle.missingModel()) {
            throw mismatch("X6 cannot bind a missing-model handle");
        }
        if (skinned() != handle.skinned()) {
            throw mismatch("X6 geometry catalog and render snapshot use different skinning modes");
        }
        if (skinned()) {
            validateSkinned(snapshot, handle);
            return snapshot.skinnedRenderSnapshot().meshCount();
        } else {
            validateStatic(snapshot, handle);
            return -1;
        }
    }

    private void validateStatic(ModelRenderSnapshot snapshot, ModelRenderHandle handle) {
        List<PreparedRenderPrimitive> handlePrimitives = Objects.requireNonNull(handle.primitives(), "handle.primitives()");
        for (X6GeometryBinding binding : bindings.values()) {
            X6GeometryBinding.StaticBinding staticBinding = (X6GeometryBinding.StaticBinding) binding;
            if (!containsIdentity(handlePrimitives, staticBinding.primitive())) {
                throw mismatch("X6 static binding does not belong to this exact prepared handle");
            }
            // This checks the exact handle node range and, if present, the exact snapshot palette.
            Minecraft2612StaticRigidRenderBackend.nodeTransformFor(snapshot, staticBinding.nodeIndex());
        }
    }

    private void validateSkinned(ModelRenderSnapshot snapshot, ModelRenderHandle rawHandle) {
        if (!(rawHandle instanceof SkinnedRenderHandle handle) || snapshot.skinnedRenderSnapshot() == null) {
            throw mismatch("A skinned X6 catalog requires a captured supported skinned render snapshot");
        }
        SkinnedRenderSnapshot skinnedSnapshot = snapshot.skinnedRenderSnapshot();
        for (X6GeometryBinding binding : bindings.values()) {
            X6GeometryBinding.SkinnedBinding skinnedBinding = (X6GeometryBinding.SkinnedBinding) binding;
            int meshIndex = skinnedBinding.snapshotMeshIndex();
            if (meshIndex >= skinnedSnapshot.meshCount()
                    || meshIndex >= handle.skinnedPrimitives().size()
                    || handle.skinnedPrimitives().get(meshIndex) != skinnedBinding.primitive()) {
                throw mismatch("X6 skinned binding does not belong to this exact prepared handle/snapshot");
            }
            // The skinned submit path does not transform this node, but a malformed catalog must
            // still fail before publication rather than carry an out-of-range primitive node.
            handle.nodeTransform(skinnedBinding.primitive().nodeIndex());
        }
        for (X6SkinnedBoneBinding bone : skinnedBoneBindings.values()) {
            if (!retainedSkinnedSkinIndexes.contains(bone.skinIndex())) {
                throw mismatch("X6 canonical bone references no skin retained by this catalog");
            }
            final int canonicalNodeIndex;
            try {
                canonicalNodeIndex = handle.skinJointNodeIndex(bone.skinIndex(), bone.jointIndex());
            } catch (IndexOutOfBoundsException exception) {
                throw mismatch("X6 canonical bone references a skin or joint outside the exact prepared handle");
            }
            if (canonicalNodeIndex != bone.nodeIndex()) {
                throw mismatch("X6 canonical bone node does not match the exact prepared skin joint");
            }
            // Exact node/palette shape validation happens before a future per-bone layer can submit.
            handle.nodeTransform(canonicalNodeIndex);
        }
    }

    private static boolean containsIdentity(List<PreparedRenderPrimitive> values, PreparedRenderPrimitive target) {
        for (PreparedRenderPrimitive value : values) {
            if (value == target) {
                return true;
            }
        }
        return false;
    }

    private static IllegalArgumentException mismatch(String detail) {
        return new IllegalArgumentException(X6DiagnosticCode.GEOMETRY_MISMATCH.code() + ": " + detail);
    }

    private static Map<BlendResourceId, X6SkinnedBoneBinding> validateLegacyStaticBones(
            Map<BlendResourceId, Integer> legacyBoneNodeIndexes) {
        Objects.requireNonNull(legacyBoneNodeIndexes, "legacyBoneNodeIndexes");
        legacyBoneNodeIndexes.forEach((boneId, nodeIndex) -> {
            X6Ids.requireId(boneId, "bone id");
            if (Objects.requireNonNull(nodeIndex, "bone node index") < 0) {
                throw new IllegalArgumentException("Prepared X6 bone node index must be non-negative");
            }
        });
        return Map.of();
    }

    private static Map<BlendResourceId, X6GeometryBinding> staticBindings(
            Map<BlendResourceId, PreparedRenderPrimitive> primitives) {
        Objects.requireNonNull(primitives, "primitives");
        Map<BlendResourceId, X6GeometryBinding> bindings = new LinkedHashMap<>();
        primitives.forEach((partId, primitive) -> bindings.put(partId, new X6GeometryBinding.StaticBinding(primitive)));
        return bindings;
    }
}
