package com.liy.blendlib.fabric.client.entity;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.core.procedural.AttachmentTopology;
import com.liy.blendlib.core.procedural.ProceduralLimits;
import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import com.liy.blendlib.fabric.client.render.RenderVisibility;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.IdentityHashMap;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Extraction-only, bounded flattening of already prepared child frames. No model lookup or animation is performed.
 * Submit consumes the immutable flat list; neither the original tree nor a runtime is traversed there.
 */
public final class BlendEntityAttachmentComposition {
    private static final BlendEntityAttachmentComposition EMPTY = new BlendEntityAttachmentComposition(List.of(), List.of());
    private final List<BlendEntityAttachment> attachments;
    private final List<Diagnostic> diagnostics;

    private BlendEntityAttachmentComposition(List<BlendEntityAttachment> attachments, List<Diagnostic> diagnostics) {
        this.attachments = List.copyOf(attachments);
        this.diagnostics = List.copyOf(diagnostics);
    }

    /** Reason an otherwise prepared child needs attention; missing-model placeholders still render. */
    public enum Reason { STALE_GENERATION, MISSING_MODEL }

    /** Captured data only, safe to retain across reloads. */
    public record Diagnostic(Reason reason, BlendModelKey modelKey, long expectedGeneration, long actualGeneration) {
        public Diagnostic {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(modelKey, "modelKey");
        }
    }

    public List<BlendEntityAttachment> attachments() { return attachments; }
    public List<Diagnostic> diagnostics() { return diagnostics; }

    /**
     * Captures at most 64 attachment occurrences at eight edges, with snapshot-identity cycles rejected before submission.
     * A mismatched-generation child and its entire subtree are omitted with a diagnostic, never resolved afresh.
     * Child lighting, material appearance and roots remain child-owned. Whole-frame visibility suppresses its
     * descendants, whereas hiding individual material slots does not. Bounds are NOT enlarged: vanilla culls
     * before extraction, so the caller must provide a conservative root/entity envelope for the whole assembly.
     */
    public static BlendEntityAttachmentComposition capture(ModelRenderSnapshot root) {
        Objects.requireNonNull(root, "root");
        if (root.attachments().isEmpty()) return EMPTY;
        List<BlendEntityAttachment> flat = new ArrayList<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        var identities = new IdentityHashMap<ModelRenderSnapshot, Integer>();
        identities.put(root, 0);
        var topology = new LinkedHashMap<Integer, LinkedHashSet<Integer>>();
        topology.put(0, new LinkedHashSet<>());
        var pending = new ArrayDeque<Pending>();
        enqueue(pending, root, BlendEntitySocketPose.IDENTITY, 1, root.visibility() == RenderVisibility.VISIBLE);
        int count = 0;
        while (!pending.isEmpty()) {
            Pending next = pending.removeLast();
            if (++count > ProceduralLimits.MAX_ATTACHMENTS) {
                throw new IllegalArgumentException("At most 64 aggregate attachment occurrences per entity snapshot");
            }
            if (next.depth() > ProceduralLimits.MAX_ATTACHMENT_DEPTH) {
                throw new IllegalArgumentException("Entity attachment composition exceeds the eight-edge depth limit");
            }
            var child = next.attachment().snapshot();
            var key = child.handle().modelKey();
            if (child.generation() != root.generation()) {
                diagnostics.add(new Diagnostic(Reason.STALE_GENERATION, key, root.generation(), child.generation()));
                continue;
            }
            int childId = identities.computeIfAbsent(child, ignored -> identities.size());
            topology.computeIfAbsent(identities.get(next.owner()), ignored -> new LinkedHashSet<>()).add(childId);
            topology.computeIfAbsent(childId, ignored -> new LinkedHashSet<>());
            if (child.handle().missingModel()) {
                diagnostics.add(new Diagnostic(Reason.MISSING_MODEL, key, root.generation(), child.generation()));
            }
            // Child socket placements already include the child's root and units-adjusted final pose.
            // Adding child.rootTransform() here would apply the root twice to grandchildren.
            var placement = compose(next.prefix(), next.attachment().placement());
            var prefix = compose(placement, next.attachment().offset());
            boolean visible = next.visible() && child.visibility() == RenderVisibility.VISIBLE;
            if (next.visible()) {
                flat.add(new BlendEntityAttachment(placement, next.attachment().offset(), child));
            }
            enqueue(pending, child, prefix, next.depth() + 1, visible);
        }
        var adjacency = new LinkedHashMap<Integer, List<Integer>>();
        topology.forEach((key, children) -> adjacency.put(key, List.copyOf(children)));
        AttachmentTopology.validate(adjacency, Comparator.naturalOrder());
        return new BlendEntityAttachmentComposition(flat, diagnostics);
    }

    private static void enqueue(ArrayDeque<Pending> pending, ModelRenderSnapshot owner,
            BlendEntitySocketPose prefix, int depth, boolean visible) {
        var children = owner.attachments();
        for (int index = children.size() - 1; index >= 0; index--) {
            pending.addLast(new Pending(owner, children.get(index), prefix, depth, visible));
        }
    }

    /** Uniform TRS composition, retaining double-precision socket translations and rejecting overflow. */
    static BlendEntitySocketPose compose(BlendEntitySocketPose parent, BlendEntitySocketPose child) {
        var a = parent.rotation();
        var b = child.rotation();
        double x = child.x() * parent.scale(), y = child.y() * parent.scale(), z = child.z() * parent.scale();
        double tx = 2 * (a.y() * z - a.z() * y);
        double ty = 2 * (a.z() * x - a.x() * z);
        double tz = 2 * (a.x() * y - a.y() * x);
        var rotation = BlendEntityRotation.normalized(
                a.w()*b.x() + a.x()*b.w() + a.y()*b.z() - a.z()*b.y(),
                a.w()*b.y() - a.x()*b.z() + a.y()*b.w() + a.z()*b.x(),
                a.w()*b.z() + a.x()*b.y() - a.y()*b.x() + a.z()*b.w(),
                a.w()*b.w() - a.x()*b.x() - a.y()*b.y() - a.z()*b.z());
        return new BlendEntitySocketPose(parent.x() + x + a.w()*tx + a.y()*tz - a.z()*ty,
                parent.y() + y + a.w()*ty + a.z()*tx - a.x()*tz,
                parent.z() + z + a.w()*tz + a.x()*ty - a.y()*tx,
                rotation, parent.scale() * child.scale());
    }

    private record Pending(ModelRenderSnapshot owner, BlendEntityAttachment attachment,
            BlendEntitySocketPose prefix, int depth, boolean visible) {}
}
