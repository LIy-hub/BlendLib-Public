package com.liy.blendlib.core.procedural;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Objects;

/** A frozen numeric bone target or a frozen semantic mesh/primitive target. */
public sealed interface ProceduralSurfaceTarget permits ProceduralSurfaceTarget.Bone,
        ProceduralSurfaceTarget.Mesh, ProceduralSurfaceTarget.Primitive {

    record Bone(int boneIndex) implements ProceduralSurfaceTarget {
        public Bone {
            if (boneIndex < 0) {
                throw new IllegalArgumentException("bone index must be non-negative");
            }
        }
    }

    record Mesh(BlendResourceId id) implements ProceduralSurfaceTarget {
        public Mesh {
            id = Objects.requireNonNull(id, "id");
            ProceduralSupport.requireId(id, "mesh id");
        }
    }

    record Primitive(BlendResourceId id) implements ProceduralSurfaceTarget {
        public Primitive {
            id = Objects.requireNonNull(id, "id");
            ProceduralSupport.requireId(id, "primitive id");
        }
    }
}
