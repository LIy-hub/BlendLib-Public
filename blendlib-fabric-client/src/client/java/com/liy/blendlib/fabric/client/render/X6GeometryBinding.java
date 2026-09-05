package com.liy.blendlib.fabric.client.render;

import java.util.Objects;

/**
 * One catalog-owned geometry identity that a prepared X6 draw may use.
 *
 * <p>Variants may only switch to another binding already retained by the same catalog. Static
 * bindings retain the original {@link StaticGeometry} identity; skinned bindings retain the exact
 * prepared primitive plus its fixed {@link SkinnedRenderSnapshot} mesh position.</p>
 */
public sealed interface X6GeometryBinding permits X6GeometryBinding.StaticBinding, X6GeometryBinding.SkinnedBinding {
    /** Node identity used to prove a mesh replacement remains compatible with its target part. */
    int nodeIndex();

    /** True only for an extraction-captured CPU-skinned mesh binding. */
    boolean skinned();

    /** Catalog-owned static/rigid primitive identity. */
    record StaticBinding(PreparedRenderPrimitive primitive) implements X6GeometryBinding {
        public StaticBinding {
            primitive = Objects.requireNonNull(primitive, "primitive");
        }

        @Override
        public int nodeIndex() {
            return primitive.nodeIndex();
        }

        @Override
        public boolean skinned() {
            return false;
        }
    }

    /** Catalog-owned skinned primitive and its frozen output position. */
    record SkinnedBinding(int snapshotMeshIndex, PreparedSkinnedRenderPrimitive primitive) implements X6GeometryBinding {
        public SkinnedBinding {
            if (snapshotMeshIndex < 0) {
                throw new IllegalArgumentException("snapshotMeshIndex must be non-negative");
            }
            primitive = Objects.requireNonNull(primitive, "primitive");
        }

        @Override
        public int nodeIndex() {
            return primitive.nodeIndex();
        }

        @Override
        public boolean skinned() {
            return true;
        }
    }
}
