package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.api.BlendResourceId;
import java.util.Optional;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable handoff from extraction/culling to a render backend.
 *
 * <p>The snapshot has no world, entity, block entity, resource manager, parser, or controller
 * reference. Submit may only consume this prepared state.</p>
 */
public final class ModelRenderSnapshot {
    private final java.util.List<com.liy.blendlib.fabric.client.entity.BlendEntityAttachment> attachments;
    private final MaterialAppearanceSnapshot materialAppearance;
    private final NamedSkinSnapshot namedSkin;
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
        this(handle, rootTransform, packedLight, packedOverlay, tintArgb, visibility, culling,
                rigidNodePalette, skinnedRenderSnapshot, presentationSocketTransform, java.util.List.of());
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
            Transform presentationSocketTransform,
            java.util.List<com.liy.blendlib.fabric.client.entity.BlendEntityAttachment> attachments) {
        this(handle, rootTransform, packedLight, packedOverlay, tintArgb, visibility, culling,
                rigidNodePalette, skinnedRenderSnapshot, presentationSocketTransform, attachments, null, null);
    }

    private ModelRenderSnapshot(
            ModelRenderHandle handle, Transform rootTransform, int packedLight, int packedOverlay,
            int tintArgb, RenderVisibility visibility, CullingMetadata culling,
            RigidNodePaletteSnapshot rigidNodePalette, SkinnedRenderSnapshot skinnedRenderSnapshot,
            Transform presentationSocketTransform,
            java.util.List<com.liy.blendlib.fabric.client.entity.BlendEntityAttachment> attachments,
            MaterialAppearanceSnapshot materialAppearance, NamedSkinSnapshot namedSkin) {
        this.namedSkin = namedSkin;
        if (namedSkin != null) namedSkin.requireCompatible(handle);
        this.materialAppearance = materialAppearance;
        if (materialAppearance != null) materialAppearance.requireCompatible(handle);
        this.attachments = java.util.List.copyOf(attachments);
        if (this.attachments.size() > 64) throw new IllegalArgumentException("At most 64 attachments per snapshot");
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

    /** Copies captured geometry with vanilla submit-time lighting; performs no animation work. */
    public ModelRenderSnapshot withLighting(int light, int overlay) {
        return new ModelRenderSnapshot(handle, rootTransform, light, overlay, tintArgb, visibility,
                culling, rigidNodePalette, skinnedRenderSnapshot, presentationSocketTransform, attachments, materialAppearance, namedSkin);
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
                Objects.requireNonNull(socketTransform, "socketTransform"), attachments, materialAppearance, namedSkin);
    }

    public java.util.List<com.liy.blendlib.fabric.client.entity.BlendEntityAttachment> attachments() {
        return attachments;
    }

    /** Copies this immutable frame with already captured child placements. */
    public ModelRenderSnapshot withAttachments(
            java.util.List<com.liy.blendlib.fabric.client.entity.BlendEntityAttachment> captured) {
        return new ModelRenderSnapshot(handle, rootTransform, packedLight, packedOverlay, tintArgb, visibility,
                culling, rigidNodePalette, skinnedRenderSnapshot, presentationSocketTransform, captured, materialAppearance, namedSkin);
    }

    /**
     * Resolves exact slot names once against this frame's handle. Unknown names retain the entire
     * authored appearance and are available through {@link #unknownMaterialSlots()}.
     * Geometry, animation, bounds and attachments are shared unchanged.
     */
    public ModelRenderSnapshot withMaterialAppearance(Map<String, MaterialSlotAppearance> selection) {
        return new ModelRenderSnapshot(handle, rootTransform, packedLight, packedOverlay, tintArgb, visibility,
                culling, rigidNodePalette, skinnedRenderSnapshot, presentationSocketTransform, attachments,
                MaterialAppearanceSnapshot.capture(handle, selection), namedSkin);
    }

    /** Captures one named skin once; unknown or invalid skins retain all authored materials. */
    public ModelRenderSnapshot withSkin(Optional<BlendResourceId> selection) {
        return new ModelRenderSnapshot(handle, rootTransform, packedLight, packedOverlay, tintArgb, visibility,
                culling, rigidNodePalette, skinnedRenderSnapshot, presentationSocketTransform, attachments,
                materialAppearance, NamedSkinSnapshot.capture(handle, selection));
    }

    /** Requested skin identity, including an invalid selection; empty for diagnostic models. */
    public Optional<BlendResourceId> selectedSkin() {
        return namedSkin == null ? Optional.empty() : namedSkin.selected();
    }

    public Optional<String> skinDiagnostic() {
        return namedSkin == null ? Optional.empty() : namedSkin.diagnostic();
    }

    RenderMaterial material(int primitiveIndex, RenderMaterial authored) {
        return namedSkin == null ? authored : namedSkin.material(primitiveIndex, authored);
    }

    /** Immutable sorted unresolved exact slot names; empty for successful or diagnostic-model captures. */
    public java.util.List<String> unknownMaterialSlots() {
        return materialAppearance == null ? java.util.List.of() : materialAppearance.unknownSlots();
    }

    MaterialSlotAppearance materialAppearance(int primitiveIndex) {
        return materialAppearance == null ? MaterialSlotAppearance.unchanged() : materialAppearance.primitive(primitiveIndex);
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
