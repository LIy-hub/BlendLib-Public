package com.liy.blendlib.core.procedural;

import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.Vec3;
import java.util.Objects;

/** Controlled operations accepted by X3; none gives a hook mutable pose or runtime access. */
public sealed interface ProceduralOperation permits ProceduralOperation.Offset, ProceduralOperation.LookAt,
        ProceduralOperation.RotationOffset, ProceduralOperation.BoneVisibility,
        ProceduralOperation.SurfaceVisibility, ProceduralOperation.SurfaceOverride {

    record Offset(int boneIndex, Vec3 translationOffset, Quaternion rotationOffset, float scaleMultiplier)
            implements ProceduralOperation {
        public Offset {
            if (boneIndex < 0) {
                throw new IllegalArgumentException("offset bone index must be non-negative");
            }
            translationOffset = Objects.requireNonNull(translationOffset, "translationOffset");
            ProceduralSupport.requireBoundedVector(translationOffset, "translationOffset");
            rotationOffset = ProceduralSupport.canonicalQuaternion(
                    Objects.requireNonNull(rotationOffset, "rotationOffset"));
            if (!Float.isFinite(scaleMultiplier) || scaleMultiplier < ProceduralLimits.MIN_SCALE_MULTIPLIER
                    || scaleMultiplier > ProceduralLimits.MAX_SCALE_MULTIPLIER) {
                throw new IllegalArgumentException("offset scale multiplier is outside X3 bounds");
            }
        }
    }

    /**
     * Aim canonical asset-space +Z at a model-space target. Yaw and pitch are clamped relative to the current local
     * +Z direction in parent space; +Y is up and positive X yaw turns canonical +Z rightward.
     */
    record LookAt(int boneIndex, Vec3 targetModelSpace, float maximumYawRadians, float maximumPitchRadians)
            implements ProceduralOperation {
        public LookAt {
            if (boneIndex < 0) {
                throw new IllegalArgumentException("look-at bone index must be non-negative");
            }
            targetModelSpace = Objects.requireNonNull(targetModelSpace, "targetModelSpace");
            ProceduralSupport.requireBoundedVector(targetModelSpace, "targetModelSpace");
            if (!Float.isFinite(maximumYawRadians) || maximumYawRadians < 0.0F || maximumYawRadians > Math.PI
                    || !Float.isFinite(maximumPitchRadians) || maximumPitchRadians < 0.0F
                    || maximumPitchRadians > Math.PI * 0.5F) {
                throw new IllegalArgumentException("look-at angular bounds are invalid");
            }
        }
    }

    /** Controlled experimental-IK output: a finite local pre-rotation applied after look-at. */
    record RotationOffset(int boneIndex, Quaternion rotationOffset) implements ProceduralOperation {
        public RotationOffset {
            if (boneIndex < 0) {
                throw new IllegalArgumentException("rotation-offset bone index must be non-negative");
            }
            rotationOffset = ProceduralSupport.canonicalQuaternion(
                    Objects.requireNonNull(rotationOffset, "rotationOffset"));
        }
    }

    record BoneVisibility(int boneIndex, boolean visible) implements ProceduralOperation {
        public BoneVisibility {
            if (boneIndex < 0) {
                throw new IllegalArgumentException("bone visibility index must be non-negative");
            }
        }
    }

    record SurfaceVisibility(ProceduralSurfaceTarget target, boolean visible) implements ProceduralOperation {
        public SurfaceVisibility {
            target = Objects.requireNonNull(target, "target");
        }
    }

    record SurfaceOverride(ProceduralSurfaceTarget target, SemanticSurfaceOverride override) implements ProceduralOperation {
        public SurfaceOverride {
            target = Objects.requireNonNull(target, "target");
            override = Objects.requireNonNull(override, "override");
        }
    }
}
