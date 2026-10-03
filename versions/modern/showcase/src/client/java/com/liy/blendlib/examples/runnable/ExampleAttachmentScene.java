package com.liy.blendlib.examples.runnable;

import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import com.liy.blendlib.fabric.client.animation.AnimationUpdateBucket;
import com.liy.blendlib.fabric.client.animation.extract.ClientSkinnedExtractionFrame;
import com.liy.blendlib.fabric.client.animation.extract.SkinnedExtractionRequest;
import com.liy.blendlib.fabric.client.animation.runtime.SkinnedAnimationRuntime;
import com.liy.blendlib.fabric.client.animation.runtime.SkinnedAnimationRuntimeInput;
import com.liy.blendlib.fabric.client.api.ClientModelLookup;
import com.liy.blendlib.fabric.client.entity.*;
import com.liy.blendlib.fabric.client.render.*;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Actual extraction callback's Minecraft-free assembly; no resources or clocks reach submit. */
public final class ExampleAttachmentScene {
    // Only the compile-time namespace comes from the common initializer; this class never
    // initializes Minecraft registration when used by asset verification or other consumers.
    public static final BlendModelKey WEAPON_MODEL = BlendModelKey.parse(ExampleContent.MOD_ID + ":marker");
    public static final BlendModelKey ORNAMENT_MODEL = BlendModelKey.parse(ExampleContent.MOD_ID + ":wand");
    public static final BlendAnimationKey WALK = BlendAnimationKey.parse(ExampleContent.MOD_ID + ":walk");
    public static final BlendResourceId TIP = BlendResourceId.parse(ExampleContent.MOD_ID + ":tip");
    public static final BlendResourceId MOUNT = BlendResourceId.parse("blendlib_runnable_examples:ornament_mount");
    // Authored in marker.glb model coordinates. The static asset has identity root/mesh nodes.
    public static final Transform MOUNT_TRANSFORM = new Transform(new Vec3(0, .5F, 0), Quaternion.IDENTITY, Vec3.ONE);
    public static final BlendEntitySocketPose WEAPON_OFFSET = new BlendEntitySocketPose(
            0, .20, 0, BlendEntityRotation.IDENTITY, .25F);
    public static final BlendEntitySocketPose ORNAMENT_OFFSET = new BlendEntitySocketPose(
            0, 0, 0, BlendEntityRotation.IDENTITY, 1.5F);

    public static final String EXTENDED_PROPERTY = "blendlib.examples.extendedAttachments";
    public static final BlendEntitySocketPose EXTENDED_WEAPON_OFFSET = new BlendEntitySocketPose(
            3, .20, 0, BlendEntityRotation.IDENTITY, .25F);
    // Includes the real descendants at every authored pose, before the actor's root rotation.
    // This is entity-local blocks, not marker/wand authored units or server collision dimensions.
    public static final BlendEntityCullingEnvelope EXTENDED_ENVELOPE =
            new BlendEntityCullingEnvelope(-4.1, -4.1, -4.1, 4.1, 4.1, 4.1);

    public enum Mode {
        DEFAULT(WEAPON_OFFSET), EXTENDED(EXTENDED_WEAPON_OFFSET);
        private final BlendEntitySocketPose weaponOffset;
        Mode(BlendEntitySocketPose weaponOffset) { this.weaponOffset = weaponOffset; }
        public BlendEntitySocketPose weaponOffset() { return weaponOffset; }
        public Optional<BlendEntityCullingEnvelope> cullingEnvelope() {
            return this == EXTENDED ? Optional.of(EXTENDED_ENVELOPE) : Optional.empty();
        }
    }

    private ExampleAttachmentScene() { }

    /** Read once when the example client is initialized, never by culling or submit. */
    public static Mode configuredMode() {
        return Boolean.getBoolean(EXTENDED_PROPERTY) ? Mode.EXTENDED : Mode.DEFAULT;
    }

    public static List<BlendEntityAttachment> capture(ClientModelLookup models, SkinnedAnimationRuntime runtime,
            BlendInstanceKey.Ephemeral ornamentKey, BlendEntitySnapshotRequest request, BlendEntitySockets sockets, int packedOverlay) {
        return capture(models, runtime, ornamentKey, request, sockets, packedOverlay, Mode.DEFAULT);
    }

    public static List<BlendEntityAttachment> capture(ClientModelLookup models, SkinnedAnimationRuntime runtime,
            BlendInstanceKey.Ephemeral ornamentKey, BlendEntitySnapshotRequest request, BlendEntitySockets sockets,
            int packedOverlay, Mode mode) {
        Objects.requireNonNull(mode, "mode");
        var tip = sockets.socket(TIP);
        var weaponModel = models.resolve(WEAPON_MODEL);
        if (tip.isEmpty() || weaponModel.missing() || weaponModel.generationId() != sockets.generation()) {
            runtime.retire(ornamentKey);
            return List.of();
        }
        var handle = weaponModel.renderHandle();
        var weapon = new ModelRenderSnapshot(handle, Transform.IDENTITY, request.packedLight(),
                packedOverlay, 0xFFFFC040, RenderVisibility.VISIBLE, new CullingMetadata(handle.bounds(), true));
        // Capture the consumer-authored static mount with exactly this weapon root and unit scale.
        var weaponRequest = new BlendEntitySnapshotRequest(WEAPON_MODEL, request.partialTick(),
                request.packedLight(), request.ageInTicks(), 0, 0, 0, request.clientGameTick(),
                request.animationVisible(), request.distanceToCameraSq());
        var weaponSockets = BlendEntitySockets.capture(weaponRequest,
                new ClientSkinnedExtractionFrame(weapon, Map.of(MOUNT, MOUNT_TRANSFORM)));
        var ornamentModel = models.resolve(ORNAMENT_MODEL);
        if (!ornamentModel.missing() && ornamentModel.generationId() == sockets.generation()
                && runtime.animationDuration(ORNAMENT_MODEL, WALK).isPresent()) {
            var ornamentHandle = ornamentModel.renderHandle();
            var input = new SkinnedAnimationRuntimeInput(ORNAMENT_MODEL, ornamentKey,
                    request.clientGameTick(), request.partialTick(), WALK, Optional.empty(),
                    AnimationUpdateBucket.VISIBLE_NEAR,
                    new SkinnedExtractionRequest(Transform.IDENTITY, request.packedLight(), packedOverlay, 0xFFFFFFFF,
                            RenderVisibility.VISIBLE, new CullingMetadata(ornamentHandle.bounds(), true)));
            var frame = runtime.extract(input);
            if (frame.isPresent() && frame.get().frame().renderSnapshot().generation() == sockets.generation()) {
                var ornament = frame.get().frame().renderSnapshot().withMaterialAppearance(Map.of(
                        "ShowcaseAnimationSurface", new MaterialSlotAppearance(0x40FFFF, true)));
                weapon = weapon.withAttachments(List.of(BlendEntityAttachment.at(
                        weaponSockets.socket(MOUNT).orElseThrow(), ORNAMENT_OFFSET, ornament)));
            } else runtime.retire(ornamentKey);
        } else runtime.retire(ornamentKey);
        return List.of(BlendEntityAttachment.at(tip.orElseThrow(), mode.weaponOffset(), weapon));
    }
}
