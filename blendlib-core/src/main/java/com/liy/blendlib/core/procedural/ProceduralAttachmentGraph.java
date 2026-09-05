package com.liy.blendlib.core.procedural;

import com.liy.blendlib.api.BlendModelKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Supplier;

/**
 * One complete, generation-scoped child-model reload graph.
 *
 * <p>A graph is not usable merely because its individual owner descriptors parsed. Every referenced child must be
 * present as an owner in the same frozen graph, every owner plan must register against this exact object, and the
 * graph must be published as one complete plan set. That makes two partial reload graphs impossible to combine into
 * a single attachment topology.</p>
 */
public final class ProceduralAttachmentGraph implements AutoCloseable {
    private static final Comparator<BlendModelKey> MODEL_ORDER = Comparator.comparing(value -> value.resourceId().value());

    private final long generation;
    private final Map<BlendModelKey, List<BlendModelKey>> childrenByModel;
    private final int maximumDepth;
    private final int edgeCount;
    /** Identity-only registrations are never exposed as model metadata or a caller-forgeable value. */
    private final Map<Object, BlendModelKey> registeredPlanOwners = new IdentityHashMap<>();
    private Lifecycle lifecycle = Lifecycle.CONFIGURING;
    private ProceduralAttachmentGenerationClaims.Claim generationClaim;

    private ProceduralAttachmentGraph(
            long generation,
            Map<BlendModelKey, List<BlendModelKey>> childrenByModel,
            int maximumDepth,
            int edgeCount) {
        this.generation = generation;
        this.childrenByModel = childrenByModel;
        this.maximumDepth = maximumDepth;
        this.edgeCount = edgeCount;
    }

    /**
     * Compiles the one complete owner map for a single resource generation. Missing children are configuration
     * failures; callers cannot rely on an implicit empty owner or stitch independent partial graphs later.
     */
    public static ProceduralAttachmentGraph compile(
            long generation,
            Map<BlendModelKey, ? extends Iterable<ProceduralAttachmentDescriptor>> attachmentsByModel) {
        if (generation < 0L) {
            throw new IllegalArgumentException("attachment graph generation must be non-negative");
        }
        Objects.requireNonNull(attachmentsByModel, "attachmentsByModel");
        if (attachmentsByModel.isEmpty() || attachmentsByModel.size() > ProceduralLimits.MAX_ATTACHMENT_GRAPH_MODELS) {
            throw new IllegalArgumentException("attachment graph owner count exceeds the X3 reload graph bound");
        }
        List<BlendModelKey> owners = new ArrayList<>(attachmentsByModel.size());
        for (BlendModelKey owner : attachmentsByModel.keySet()) {
            owners.add(Objects.requireNonNull(owner, "attachment graph owner"));
        }
        owners.sort(MODEL_ORDER);

        LinkedHashMap<BlendModelKey, List<BlendModelKey>> graph = new LinkedHashMap<>();
        int edgeCount = 0;
        int rawDescriptorVisits = 0;
        for (BlendModelKey owner : owners) {
            Iterable<ProceduralAttachmentDescriptor> source = Objects.requireNonNull(
                    attachmentsByModel.get(owner), "attachment graph descriptors");
            LinkedHashSet<BlendModelKey> children = new LinkedHashSet<>();
            Iterator<ProceduralAttachmentDescriptor> descriptors = Objects.requireNonNull(
                    source.iterator(), "attachment graph descriptor iterator");
            while (descriptors.hasNext()) {
                ProceduralAttachmentDescriptor descriptor = descriptors.next();
                rawDescriptorVisits = Math.incrementExact(rawDescriptorVisits);
                if (rawDescriptorVisits > ProceduralLimits.MAX_ATTACHMENT_GRAPH_RAW_DESCRIPTOR_VISITS) {
                    throw new IllegalArgumentException("attachment graph raw descriptor visit count exceeds the X3 reload graph bound");
                }
                descriptor = Objects.requireNonNull(descriptor, "attachment graph descriptor");
                if (descriptor.payload() instanceof ProceduralAttachmentPayload.ChildModel child) {
                    children.add(child.modelKey());
                }
            }
            List<BlendModelKey> orderedChildren = new ArrayList<>(children);
            orderedChildren.sort(MODEL_ORDER);
            edgeCount = Math.addExact(edgeCount, orderedChildren.size());
            if (edgeCount > ProceduralLimits.MAX_ATTACHMENT_GRAPH_EDGES) {
                throw new IllegalArgumentException("attachment graph edge count exceeds the X3 reload graph bound");
            }
            graph.put(owner, List.copyOf(orderedChildren));
        }
        for (Map.Entry<BlendModelKey, List<BlendModelKey>> entry : graph.entrySet()) {
            for (BlendModelKey child : entry.getValue()) {
                if (!graph.containsKey(child)) {
                    throw new IllegalArgumentException("attachment graph is incomplete: child owner is absent from this reload generation");
                }
            }
        }
        int maximumDepth = validateTopologyIteratively(graph);
        LinkedHashMap<BlendModelKey, List<BlendModelKey>> frozen = new LinkedHashMap<>();
        graph.forEach((owner, children) -> frozen.put(owner, children));
        return new ProceduralAttachmentGraph(generation, Collections.unmodifiableMap(frozen), maximumDepth, edgeCount);
    }

    static ProceduralAttachmentGraph empty(BlendModelKey owner, long generation) {
        return compile(generation, Map.of(Objects.requireNonNull(owner, "owner"), List.of()));
    }

    /**
     * Seals one exact, complete set of graph-owned plans for publication. A plan from another graph, generation, or
     * partial owner set fails before any runtime can consume a child-model topology.
     */
    public static void publish(List<ProceduralRigPlan> plans) {
        Objects.requireNonNull(plans, "plans");
        if (plans.isEmpty()) {
            throw new IllegalArgumentException("attachment graph publication requires at least one plan");
        }
        ProceduralAttachmentGraph graph = null;
        for (ProceduralRigPlan plan : plans) {
            plan = Objects.requireNonNull(plan, "attachment graph plan");
            if (graph == null) {
                graph = plan.attachmentGraph();
            } else if (graph != plan.attachmentGraph()) {
                throw new IllegalArgumentException("plans from different frozen attachment graph snapshots cannot be published together");
            }
        }
        graph.publishCompletePlans(plans);
    }

    public long generation() {
        return generation;
    }

    /** Returns a deterministic immutable direct-child view for configuration diagnostics and tests. */
    public synchronized List<BlendModelKey> childrenOf(BlendModelKey owner) {
        requireNotRetired();
        return childrenByModel.getOrDefault(Objects.requireNonNull(owner, "owner"), List.of());
    }

    /** Returns the validated longest directed path in attachment edges, where eight edges are legal. */
    public synchronized int maximumDepth() {
        requireNotRetired();
        return maximumDepth;
    }

    synchronized void requireExactDirectChildren(
            BlendModelKey owner,
            long planGeneration,
            List<ProceduralAttachmentDescriptor> attachments) {
        requireNotRetired();
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(attachments, "attachments");
        if (generation != planGeneration) {
            throw new IllegalArgumentException("attachment graph generation does not match this frozen rig plan");
        }
        if (!childrenByModel.containsKey(owner)) {
            throw new IllegalArgumentException("frozen attachment graph does not own this rig plan model");
        }
        LinkedHashSet<BlendModelKey> expected = new LinkedHashSet<>();
        for (ProceduralAttachmentDescriptor attachment : attachments) {
            if (attachment.payload() instanceof ProceduralAttachmentPayload.ChildModel child) {
                expected.add(child.modelKey());
            }
        }
        List<BlendModelKey> orderedExpected = new ArrayList<>(expected);
        orderedExpected.sort(MODEL_ORDER);
        if (!childrenByModel.get(owner).equals(List.copyOf(orderedExpected))) {
            throw new IllegalArgumentException("frozen child-model graph does not exactly match this plan's attachment edges");
        }
    }

    synchronized void registerPlan(
            BlendModelKey owner,
            long planGeneration,
            List<ProceduralAttachmentDescriptor> attachments,
            Object planIdentity) {
        requireNotRetired();
        if (lifecycle == Lifecycle.PUBLISHED) {
            throw new IllegalArgumentException("attachment graph is already published and cannot accept another plan");
        }
        requireExactDirectChildren(owner, planGeneration, attachments);
        Objects.requireNonNull(planIdentity, "planIdentity");
        for (BlendModelKey registeredOwner : registeredPlanOwners.values()) {
            if (registeredOwner.equals(owner)) {
                throw new IllegalArgumentException("attachment graph already has a registered plan for this owner model");
            }
        }
        registeredPlanOwners.put(planIdentity, owner);
    }

    synchronized void requireRuntimePlan(Object planIdentity) {
        requireNotRetired();
        if (!registeredPlanOwners.containsKey(Objects.requireNonNull(planIdentity, "planIdentity"))) {
            throw new IllegalArgumentException("runtime plan is not registered with this exact attachment graph");
        }
        if (requiresPublicationInternal() && lifecycle != Lifecycle.PUBLISHED) {
            throw new IllegalArgumentException(
                    "child-model rig plans must be published through one complete attachment reload graph");
        }
    }

    synchronized boolean isActivePlan(Object planIdentity) {
        return lifecycle != Lifecycle.RETIRED
                && registeredPlanOwners.containsKey(planIdentity)
                && (!requiresPublicationInternal() || lifecycle == Lifecycle.PUBLISHED);
    }

    synchronized <T> T readIfActive(Object planIdentity, Supplier<T> reader) {
        if (!isActivePlan(planIdentity)) {
            throw new IllegalStateException("attachment graph is retired or does not own this runtime plan");
        }
        return Objects.requireNonNull(reader, "reader").get();
    }

    synchronized boolean commitIfActive(Object planIdentity, Runnable commit) {
        if (!isActivePlan(planIdentity)) {
            return false;
        }
        Objects.requireNonNull(commit, "commit").run();
        return true;
    }

    private synchronized void publishCompletePlans(List<ProceduralRigPlan> plans) {
        requireNotRetired();
        validateCompletePlans(plans);
        if (lifecycle == Lifecycle.PUBLISHED) {
            return;
        }
        ProceduralAttachmentGenerationClaims.Claim claim = null;
        if (requiresPublicationInternal()) {
            claim = ProceduralAttachmentGenerationClaims.claim(generation, this);
        }
        generationClaim = claim;
        lifecycle = Lifecycle.PUBLISHED;
    }

    private void validateCompletePlans(List<ProceduralRigPlan> plans) {
        if (plans.size() != childrenByModel.size() || registeredPlanOwners.size() != childrenByModel.size()) {
            throw new IllegalArgumentException("attachment graph publication is missing one or more complete owner plans");
        }
        Set<BlendModelKey> owners = new LinkedHashSet<>();
        for (ProceduralRigPlan plan : plans) {
            if (plan.attachmentGraph() != this || plan.generation() != generation
                    || !registeredPlanOwners.containsKey(plan.snapshotPlanIdentity())
                    || !owners.add(plan.modelKey())) {
                throw new IllegalArgumentException("attachment graph publication contains a foreign, stale, or duplicate rig plan");
            }
        }
        if (!owners.equals(childrenByModel.keySet()) || !new LinkedHashSet<>(registeredPlanOwners.values()).equals(owners)) {
            throw new IllegalArgumentException("attachment graph publication does not cover the exact frozen owner set");
        }
    }

    /**
     * Permanently retires this graph and releases its generation claim. The operation is idempotent; a retired graph
     * can never publish, register a new plan, construct another runtime, or commit another frame.
     */
    @Override
    public synchronized void close() {
        if (lifecycle == Lifecycle.RETIRED) {
            return;
        }
        lifecycle = Lifecycle.RETIRED;
        ProceduralAttachmentGenerationClaims.Claim claim = generationClaim;
        generationClaim = null;
        if (claim != null) {
            ProceduralAttachmentGenerationClaims.release(generation, claim);
        }
    }

    private boolean requiresPublicationInternal() {
        return childrenByModel.size() > 1 || edgeCount > 0;
    }

    private void requireNotRetired() {
        if (lifecycle == Lifecycle.RETIRED) {
            throw new IllegalStateException("attachment graph is permanently retired");
        }
    }

    private static int validateTopologyIteratively(Map<BlendModelKey, List<BlendModelKey>> graph) {
        LinkedHashMap<BlendModelKey, Integer> incoming = new LinkedHashMap<>();
        LinkedHashMap<BlendModelKey, Integer> depths = new LinkedHashMap<>();
        for (BlendModelKey owner : graph.keySet()) {
            incoming.put(owner, 0);
            depths.put(owner, 0);
        }
        for (List<BlendModelKey> children : graph.values()) {
            for (BlendModelKey child : children) {
                incoming.put(child, Math.addExact(incoming.get(child), 1));
            }
        }
        PriorityQueue<BlendModelKey> ready = new PriorityQueue<>(MODEL_ORDER);
        for (Map.Entry<BlendModelKey, Integer> entry : incoming.entrySet()) {
            if (entry.getValue() == 0) {
                ready.add(entry.getKey());
            }
        }
        int processed = 0;
        int maximumDepth = 0;
        while (!ready.isEmpty()) {
            BlendModelKey owner = ready.remove();
            processed++;
            int ownerDepth = depths.get(owner);
            maximumDepth = Math.max(maximumDepth, ownerDepth);
            for (BlendModelKey child : graph.get(owner)) {
                int childDepth = Math.max(depths.get(child), Math.addExact(ownerDepth, 1));
                if (childDepth > ProceduralLimits.MAX_ATTACHMENT_DEPTH) {
                    throw new IllegalArgumentException("child-model attachment graph exceeds the X3 eight-edge depth limit");
                }
                depths.put(child, childDepth);
                int remaining = incoming.get(child) - 1;
                incoming.put(child, remaining);
                if (remaining == 0) {
                    ready.add(child);
                }
            }
        }
        if (processed != graph.size()) {
            throw new IllegalArgumentException("child-model attachment graph contains a directed cycle");
        }
        return maximumDepth;
    }

    private enum Lifecycle {
        CONFIGURING,
        PUBLISHED,
        RETIRED
    }
}
