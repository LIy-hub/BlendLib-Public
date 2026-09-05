package com.liy.blendlib.core.procedural;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.v2.BoneSchema;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Configuration-time frozen X3 rig plan. All string-to-slot work has completed before this type is published.
 */
public final class ProceduralRigPlan {
    /** Opaque per-plan identity; never exposed to callers or inferred from model/generation/cardinality. */
    private final Object snapshotPlanIdentity = new Object();
    private final BlendModelKey modelKey;
    private final long generation;
    private final BoneSchema schema;
    private final int[] parents;
    private final List<ProceduralSocketDefinition> sockets;
    private final Map<BlendResourceId, ProceduralSocketDefinition> socketsById;
    private final Map<BlendResourceId, ProceduralSurfaceDefinition> meshesById;
    private final Map<BlendResourceId, ProceduralSurfaceDefinition> primitivesById;
    private final List<ProceduralHookBinding> hooks;
    private final List<ProceduralAttachmentDescriptor> attachments;
    private final ProceduralAttachmentGraph attachmentGraph;
    private final List<ProceduralVisualEventMarker> visualEventMarkers;

    public ProceduralRigPlan(
            BlendModelKey modelKey,
            long generation,
            BoneSchema schema,
            int[] parents,
            List<ProceduralSocketDefinition> sockets,
            List<ProceduralSurfaceDefinition> meshes,
            List<ProceduralSurfaceDefinition> primitives,
            List<? extends ProceduralHook> hooks) {
        this(modelKey, generation, schema, parents, sockets, meshes, primitives, hooks, List.of(),
                ProceduralAttachmentGraph.empty(modelKey, generation), List.of());
    }

    public ProceduralRigPlan(
            BlendModelKey modelKey,
            long generation,
            BoneSchema schema,
            int[] parents,
            List<ProceduralSocketDefinition> sockets,
            List<ProceduralSurfaceDefinition> meshes,
            List<ProceduralSurfaceDefinition> primitives,
            List<? extends ProceduralHook> hooks,
            List<ProceduralAttachmentDescriptor> attachments) {
        this(modelKey, generation, schema, parents, sockets, meshes, primitives, hooks, attachments,
                defaultSingleModelGraph(modelKey, generation, attachments), List.of());
    }

    /**
     * Freezes a public, single-model X3 marker catalog. This convenient constructor is deliberately unavailable to
     * child-model topology: any {@link ProceduralAttachmentPayload.ChildModel} descriptor is rejected before a plan
     * exists, so multi-model reloads must use {@link ProceduralAttachmentGraph#compile(long, Map)} and
     * {@link ProceduralAttachmentGraph#publish(List)} with one complete graph.
     */
    public ProceduralRigPlan(
            BlendModelKey modelKey,
            long generation,
            BoneSchema schema,
            int[] parents,
            List<ProceduralSocketDefinition> sockets,
            List<ProceduralSurfaceDefinition> meshes,
            List<ProceduralSurfaceDefinition> primitives,
            List<? extends ProceduralHook> hooks,
            List<ProceduralAttachmentDescriptor> attachments,
            List<ProceduralVisualEventMarker> visualEventMarkers) {
        this(modelKey, generation, schema, parents, sockets, meshes, primitives, hooks, attachments,
                defaultSingleModelGraph(modelKey, generation, attachments), visualEventMarkers);
    }

    public ProceduralRigPlan(
            BlendModelKey modelKey,
            long generation,
            BoneSchema schema,
            int[] parents,
            List<ProceduralSocketDefinition> sockets,
            List<ProceduralSurfaceDefinition> meshes,
            List<ProceduralSurfaceDefinition> primitives,
            List<? extends ProceduralHook> hooks,
            List<ProceduralAttachmentDescriptor> attachments,
            ProceduralAttachmentGraph attachmentGraph) {
        this(modelKey, generation, schema, parents, sockets, meshes, primitives, hooks, attachments, attachmentGraph, List.of());
    }

    /**
     * Freezes X3 presentation marker configuration beside the rig topology. Marker payloads are not frame events:
     * an exact bound X2 runtime must still derive every occurrence from its current immutable playhead snapshot.
     */
    public ProceduralRigPlan(
            BlendModelKey modelKey,
            long generation,
            BoneSchema schema,
            int[] parents,
            List<ProceduralSocketDefinition> sockets,
            List<ProceduralSurfaceDefinition> meshes,
            List<ProceduralSurfaceDefinition> primitives,
            List<? extends ProceduralHook> hooks,
            List<ProceduralAttachmentDescriptor> attachments,
            ProceduralAttachmentGraph attachmentGraph,
            List<ProceduralVisualEventMarker> visualEventMarkers) {
        this.modelKey = Objects.requireNonNull(modelKey, "modelKey");
        ProceduralSupport.requireId(modelKey.resourceId(), "model key");
        if (generation < 0L) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
        this.generation = generation;
        this.schema = Objects.requireNonNull(schema, "schema");
        this.parents = validateParents(parents, schema.boneCount());
        this.sockets = freezeSockets(sockets, schema.boneCount());
        this.socketsById = indexSockets(this.sockets);
        this.meshesById = freezeSurfaces(meshes, ProceduralSurfaceKind.MESH, schema.boneCount());
        this.primitivesById = freezeSurfaces(primitives, ProceduralSurfaceKind.PRIMITIVE, schema.boneCount());
        this.hooks = freezeHooks(hooks);
        this.attachments = freezeAttachments(attachments);
        this.attachmentGraph = Objects.requireNonNull(attachmentGraph, "attachmentGraph");
        this.attachmentGraph.requireExactDirectChildren(modelKey, generation, this.attachments);
        this.visualEventMarkers = freezeVisualEventMarkers(visualEventMarkers);
        // All local validation must complete before this plan claims a graph owner slot. A failed marker catalog must
        // not poison a caller-held complete reload graph and make its later valid publication impossible.
        this.attachmentGraph.registerPlan(modelKey, generation, this.attachments, snapshotPlanIdentity);
    }

    public BlendModelKey modelKey() {
        return modelKey;
    }

    public long generation() {
        return generation;
    }

    public BoneSchema schema() {
        return schema;
    }

    public int parentOf(int boneIndex) {
        requireBoneIndex(boneIndex);
        return parents[boneIndex];
    }

    public List<ProceduralSocketDefinition> sockets() {
        return sockets;
    }

    public List<ProceduralAttachmentDescriptor> attachments() {
        return attachments;
    }

    /** The complete configuration-validated child-model topology consumed by this frozen plan. */
    public ProceduralAttachmentGraph attachmentGraph() {
        return attachmentGraph;
    }

    /** Immutable Experimental X3 configuration; events themselves remain runtime-derived and non-forgeable. */
    public List<ProceduralVisualEventMarker> visualEventMarkers() {
        return visualEventMarkers;
    }

    List<ProceduralHookBinding> hooks() {
        return hooks;
    }

    ProceduralSocketDefinition socket(BlendResourceId id) {
        return socketsById.get(id);
    }

    boolean isKnownTarget(ProceduralSurfaceTarget target) {
        return switch (target) {
            case ProceduralSurfaceTarget.Bone bone -> bone.boneIndex() < schema.boneCount();
            case ProceduralSurfaceTarget.Mesh mesh -> meshesById.containsKey(mesh.id());
            case ProceduralSurfaceTarget.Primitive primitive -> primitivesById.containsKey(primitive.id());
        };
    }

    int owningBone(ProceduralSurfaceTarget target) {
        return switch (target) {
            case ProceduralSurfaceTarget.Bone bone -> bone.boneIndex();
            case ProceduralSurfaceTarget.Mesh mesh -> meshesById.get(mesh.id()).boneIndex();
            case ProceduralSurfaceTarget.Primitive primitive -> primitivesById.get(primitive.id()).boneIndex();
        };
    }

    Object snapshotPlanIdentity() {
        return snapshotPlanIdentity;
    }

    boolean matchesSnapshotPlanIdentity(Object identity) {
        return snapshotPlanIdentity == identity;
    }

    Map<ProceduralSurfaceTarget, Boolean> initialSurfaceVisibility() {
        LinkedHashMap<ProceduralSurfaceTarget, Boolean> result = new LinkedHashMap<>();
        meshesById.values().stream()
                .sorted(Comparator.comparing(value -> value.id().value()))
                .forEach(value -> result.put(new ProceduralSurfaceTarget.Mesh(value.id()), Boolean.TRUE));
        primitivesById.values().stream()
                .sorted(Comparator.comparing(value -> value.id().value()))
                .forEach(value -> result.put(new ProceduralSurfaceTarget.Primitive(value.id()), Boolean.TRUE));
        return result;
    }

    void requireBoneIndex(int boneIndex) {
        if (boneIndex < 0 || boneIndex >= schema.boneCount()) {
            throw new IllegalArgumentException("bone target is outside frozen schema bounds: " + boneIndex);
        }
    }

    private static int[] validateParents(int[] source, int boneCount) {
        Objects.requireNonNull(source, "parents");
        if (source.length != boneCount) {
            throw new IllegalArgumentException("parent hierarchy and bone schema must have equal cardinality");
        }
        int[] copied = source.clone();
        for (int index = 0; index < copied.length; index++) {
            int parent = copied[index];
            if (parent < -1 || parent >= copied.length || parent == index) {
                throw new IllegalArgumentException("parent hierarchy contains an invalid bone index");
            }
            int depth = 0;
            int cursor = index;
            boolean[] seen = new boolean[copied.length];
            while (cursor != -1) {
                if (seen[cursor]) {
                    throw new IllegalArgumentException("parent hierarchy contains a cycle");
                }
                seen[cursor] = true;
                if (++depth > ProceduralLimits.MAX_HIERARCHY_DEPTH) {
                    throw new IllegalArgumentException("parent hierarchy exceeds X3 depth limit");
                }
                cursor = copied[cursor];
            }
        }
        return copied;
    }

    private static List<ProceduralSocketDefinition> freezeSockets(List<ProceduralSocketDefinition> source, int boneCount) {
        Objects.requireNonNull(source, "sockets");
        LinkedHashMap<BlendResourceId, ProceduralSocketDefinition> indexed = new LinkedHashMap<>();
        for (ProceduralSocketDefinition socket : source) {
            socket = Objects.requireNonNull(socket, "socket");
            if (socket.boneIndex() >= boneCount) {
                throw new IllegalArgumentException("socket bone target is outside frozen schema bounds");
            }
            if (indexed.putIfAbsent(socket.id(), socket) != null) {
                throw new IllegalArgumentException("duplicate socket id");
            }
        }
        List<ProceduralSocketDefinition> ordered = new ArrayList<>(indexed.values());
        ordered.sort(Comparator.comparing(value -> value.id().value()));
        return List.copyOf(ordered);
    }

    private static Map<BlendResourceId, ProceduralSocketDefinition> indexSockets(List<ProceduralSocketDefinition> sockets) {
        LinkedHashMap<BlendResourceId, ProceduralSocketDefinition> indexed = new LinkedHashMap<>();
        for (ProceduralSocketDefinition socket : sockets) {
            indexed.put(socket.id(), socket);
        }
        return Collections.unmodifiableMap(indexed);
    }

    private static Map<BlendResourceId, ProceduralSurfaceDefinition> freezeSurfaces(
            List<ProceduralSurfaceDefinition> source, ProceduralSurfaceKind expectedKind, int boneCount) {
        Objects.requireNonNull(source, "surfaces");
        List<ProceduralSurfaceDefinition> sorted = new ArrayList<>();
        for (ProceduralSurfaceDefinition surface : source) {
            surface = Objects.requireNonNull(surface, "surface");
            if (surface.kind() != expectedKind) {
                throw new IllegalArgumentException("surface list contains the wrong semantic target kind");
            }
            if (surface.boneIndex() >= boneCount) {
                throw new IllegalArgumentException("surface owning bone is outside frozen schema bounds");
            }
            sorted.add(surface);
        }
        sorted.sort(Comparator.comparing(value -> value.id().value()));
        LinkedHashMap<BlendResourceId, ProceduralSurfaceDefinition> indexed = new LinkedHashMap<>();
        for (ProceduralSurfaceDefinition surface : sorted) {
            if (indexed.putIfAbsent(surface.id(), surface) != null) {
                throw new IllegalArgumentException("duplicate surface id");
            }
        }
        return Collections.unmodifiableMap(indexed);
    }

    private static List<ProceduralHookBinding> freezeHooks(List<? extends ProceduralHook> source) {
        Objects.requireNonNull(source, "hooks");
        if (source.size() > ProceduralLimits.MAX_HOOKS) {
            throw new IllegalArgumentException("hook count exceeds X3 limit");
        }
        List<ProceduralHookBinding> copied = new ArrayList<>(source.size());
        LinkedHashMap<BlendResourceId, Boolean> identities = new LinkedHashMap<>();
        for (ProceduralHook hook : source) {
            hook = Objects.requireNonNull(hook, "hook");
            BlendResourceId id;
            int priority;
            try {
                id = hook.id();
                ProceduralSupport.requireId(id, "hook id");
                priority = hook.priority();
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("hook identity cannot be frozen", exception);
            }
            if (identities.putIfAbsent(id, Boolean.TRUE) != null) {
                throw new IllegalArgumentException("duplicate hook id");
            }
            copied.add(new ProceduralHookBinding(hook, id, priority));
        }
        copied.sort(Comparator.comparingInt(ProceduralHookBinding::priority)
                .thenComparing(value -> value.id().value()));
        return List.copyOf(copied);
    }

    private List<ProceduralAttachmentDescriptor> freezeAttachments(List<ProceduralAttachmentDescriptor> source) {
        Objects.requireNonNull(source, "attachments");
        if (source.size() > ProceduralLimits.MAX_ATTACHMENTS) {
            throw new IllegalArgumentException("attachment count exceeds X3 limit");
        }
        List<ProceduralAttachmentDescriptor> copied = new ArrayList<>(source.size());
        LinkedHashMap<BlendResourceId, Boolean> ids = new LinkedHashMap<>();
        for (ProceduralAttachmentDescriptor attachment : source) {
            attachment = Objects.requireNonNull(attachment, "attachment");
            if (ids.putIfAbsent(attachment.id(), Boolean.TRUE) != null) {
                throw new IllegalArgumentException("duplicate attachment id");
            }
            if (attachment.anchor() instanceof ProceduralAttachmentAnchor.Bone bone) {
                requireBoneIndex(bone.boneIndex());
            } else if (attachment.anchor() instanceof ProceduralAttachmentAnchor.Socket socket && !socketsById.containsKey(socket.socketId())) {
                throw new IllegalArgumentException("attachment references an unknown frozen socket");
            }
            copied.add(attachment);
        }
        copied.sort(Comparator.comparing(value -> value.id().value()));
        return List.copyOf(copied);
    }

    private static List<ProceduralVisualEventMarker> freezeVisualEventMarkers(List<ProceduralVisualEventMarker> source) {
        Objects.requireNonNull(source, "visualEventMarkers");
        if (source.size() > ProceduralLimits.MAX_VISUAL_EVENTS_PER_FRAME) {
            throw new IllegalArgumentException("visual event marker count exceeds the X3 frame event bound");
        }
        List<ProceduralVisualEventMarker> copied = new ArrayList<>(source.size());
        for (ProceduralVisualEventMarker marker : source) {
            copied.add(Objects.requireNonNull(marker, "visualEventMarker"));
        }
        return List.copyOf(copied);
    }

    private static ProceduralAttachmentGraph defaultSingleModelGraph(
            BlendModelKey modelKey,
            long generation,
            List<ProceduralAttachmentDescriptor> attachments) {
        Objects.requireNonNull(attachments, "attachments");
        for (ProceduralAttachmentDescriptor attachment : attachments) {
            attachment = Objects.requireNonNull(attachment, "attachment");
            if (attachment.payload() instanceof ProceduralAttachmentPayload.ChildModel) {
                throw new IllegalArgumentException(
                        "child-model attachments require one complete ProceduralAttachmentGraph compile and publish");
            }
        }
        return ProceduralAttachmentGraph.empty(modelKey, generation);
    }

    record ProceduralHookBinding(ProceduralHook hook, BlendResourceId id, int priority) {
    }
}
