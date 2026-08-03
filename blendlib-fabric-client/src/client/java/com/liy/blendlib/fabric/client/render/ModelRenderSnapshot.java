package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.core.model.Transform;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable handoff from extraction/culling to a render backend.
 *
 * <p>The snapshot has no world, entity, block entity, resource manager, parser, or controller
 * reference. Submit may only consume this prepared state.</p>
 */
public final class ModelRenderSnapshot {
    private final ModelRenderHandle handle;
    private final Transform rootTransform;
    private final int packedLight;
    private final int packedOverlay;
    private final int tintArgb;
    private final RenderVisibility visibility;
    private final CullingMetadata culling;
    private final RigidNodePaletteSnapshot rigidNodePalette;
    private final SkinnedRenderSnapshot skinnedRenderSnapshot;
    private final Transform presentationSocketTransform;

    public ModelRenderSnapshot(
            ModelRenderHandle handle,
            Transform rootTransform,
            int packedLight,
            int packedOverlay,
            int tintArgb,
            RenderVisibility visibility,
            CullingMetadata culling) {
        this(handle, rootTransform, packedLight, packedOverlay, tintArgb, visibility, culling, null, null, null);
    }

    /**
     * Internal extraction-to-backend constructor for an already-sampled rigid-node palette.
     *
     * <p>The palette is fail-fast bound to this immutable handle's model key and generation before
     * render code can observe it.</p>
     */
    ModelRenderSnapshot(
            ModelRenderHandle handle,
            Transform rootTransform,
            int packedLight,
            int packedOverlay,
            int tintArgb,
            RenderVisibility visibility,
            CullingMetadata culling,
            RigidNodePaletteSnapshot rigidNodePalette) {
        this(handle, rootTransform, packedLight, packedOverlay, tintArgb, visibility, culling, rigidNodePalette, null, null);
    }

    /**
     * Freezes precomputed rigid-node world transforms for one exact prepared handle generation.
     *
     * <p>The caller supplies only immutable rendering inputs and canonical transforms already
     * derived during extraction. This method performs no lifecycle lookup, controller work, or
     * pose computation; compatibility is checked by the captured palette constructor.</p>
     */
    public static ModelRenderSnapshot rigid(
            StaticRigidRenderHandle handle,
            Transform rootTransform,
            int packedLight,
            int packedOverlay,
            int tintArgb,
            RenderVisibility visibility,
            CullingMetadata culling,
            Map<Integer, Transform> canonicalWorldTransforms) {
        StaticRigidRenderHandle checkedHandle = Objects.requireNonNull(handle, "handle");
        return new ModelRenderSnapshot(
                checkedHandle,
                rootTransform,
                packedLight,
                packedOverlay,
                tintArgb,
                visibility,
                culling,
                RigidNodePaletteSnapshot.copyOf(
                        checkedHandle.modelKey(), checkedHandle.generation(), canonicalWorldTransforms));
    }

    private ModelRenderSnapshot(
            ModelRenderHandle handle,
            Transform rootTransform,
            int packedLight,
            int packedOverlay,
            int tintArgb,
            RenderVisibility visibility,
            CullingMetadata culling,
            RigidNodePaletteSnapshot rigidNodePalette,
            SkinnedRenderSnapshot skinnedRenderSnapshot,
            Transform presentationSocketTransform) {
        this.handle = Objects.requireNonNull(handle, "handle");
        this.rootTransform = Objects.requireNonNull(rootTransform, "rootTransform");
        this.packedLight = packedLight;
        this.packedOverlay = packedOverlay;
        this.tintArgb = tintArgb;
        this.visibility = Objects.requireNonNull(visibility, "visibility");
        this.culling = Objects.requireNonNull(culling, "culling");
        if (rigidNodePalette != null) {
            rigidNodePalette.requireCompatible(this.handle);
        }
        if (rigidNodePalette != null && skinnedRenderSnapshot != null) {
            throw new IllegalArgumentException("A render snapshot cannot contain both rigid and skinned palettes");
        }
        if (this.handle.skinned()) {
            if (!(this.handle instanceof SkinnedRenderHandle skinnedHandle)) {
                throw new IllegalArgumentException("A skinned render handle must use the supported CPU-skinning adapter handle");
            }
            if (skinnedRenderSnapshot == null) {
                throw new IllegalArgumentException("A skinned render handle requires a captured skinned render snapshot");
            }
            skinnedRenderSnapshot.requireCompatible(skinnedHandle);
        } else if (skinnedRenderSnapshot != null) {
            throw new IllegalArgumentException("Only a skinned render handle may carry a skinned render snapshot");
        }
        if (presentationSocketTransform != null && !this.handle.skinned()) {
            throw new IllegalArgumentException("Only a skinned render snapshot may carry a presentation socket transform");
        }
        this.rigidNodePalette = rigidNodePalette;
        this.skinnedRenderSnapshot = skinnedRenderSnapshot;
        this.presentationSocketTransform = presentationSocketTransform;
    }

    /**
     * Creates a snapshot from already captured CPU-skinned output for one exact handle generation.
     *
     * <p>The caller must do controller advancement, pose sampling, palette construction, and CPU
     * skinning before this method. Submit receives only this immutable handoff.</p>
     */
    public static ModelRenderSnapshot skinned(
            SkinnedRenderHandle handle,
            Transform rootTransform,
            int packedLight,
            int packedOverlay,
            int tintArgb,
            RenderVisibility visibility,
            CullingMetadata culling,
            SkinnedRenderSnapshot skinnedRenderSnapshot) {
        return new ModelRenderSnapshot(
                handle,
                rootTransform,
                packedLight,
                packedOverlay,
                tintArgb,
                visibility,
                culling,
                null,
                skinnedRenderSnapshot,
                null);
    }

    /**
     * Returns a copy carrying one extraction-captured socket transform for client presentation.
     *
     * <p>The transform is already sampled in canonical model space. Submit may consume it only
     * with this snapshot's root transform and prepared unit conversion; it must not resolve a
     * socket, access an entity/world, or sample animation again.</p>
     */
    public ModelRenderSnapshot withPresentationSocketTransform(Transform socketTransform) {
        if (!handle.skinned() || skinnedRenderSnapshot == null) {
            throw new IllegalStateException("Only a captured skinned render snapshot may carry a presentation socket transform");
        }
        return new ModelRenderSnapshot(
                handle,
                rootTransform,
                packedLight,
                packedOverlay,
                tintArgb,
                visibility,
                culling,
                rigidNodePalette,
                skinnedRenderSnapshot,
                Objects.requireNonNull(socketTransform, "socketTransform"));
    }

    public ModelRenderHandle handle() {
        return handle;
    }

    /** Generation is carried by the immutable render handle to prevent stale-resource mixing. */
    public long generation() {
        return handle.generation();
    }

    public Transform rootTransform() {
        return rootTransform;
    }

    public int packedLight() {
        return packedLight;
    }

    public int packedOverlay() {
        return packedOverlay;
    }

    public int tintArgb() {
        return tintArgb;
    }

    public RenderVisibility visibility() {
        return visibility;
    }

    public CullingMetadata culling() {
        return culling;
    }

    /** Package-private internal handoff; the public seven-argument constructor remains rest pose. */
    RigidNodePaletteSnapshot rigidNodePalette() {
        return rigidNodePalette;
    }

    /** Package-private internal handoff for the P5 CPU-skinned submit path. */
    SkinnedRenderSnapshot skinnedRenderSnapshot() {
        return skinnedRenderSnapshot;
    }

    /** Package-private render handoff for a configured P5 presentation-only socket marker. */
    Transform presentationSocketTransformOrNull() {
        return presentationSocketTransform;
    }
}
