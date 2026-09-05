package com.liy.blendlib.fabric.client.reload;

import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import com.liy.blendlib.fabric.client.render.PreparedRenderPrimitive;
import com.liy.blendlib.fabric.client.render.RenderLayer;
import com.liy.blendlib.fabric.client.render.RenderMaterial;
import com.liy.blendlib.fabric.client.render.RenderVisibility;
import com.liy.blendlib.fabric.client.render.X6DrawPrimitive;
import com.liy.blendlib.fabric.client.render.X6GeometryBinding;
import java.util.Objects;
import net.minecraft.client.renderer.texture.OverlayTexture;

/**
 * One exact direct-flat eligibility marker for a prepared static draw.
 *
 * <p>The marker is deliberately instance-bound rather than reconstructed from value equality.  A future Stage A
 * owner must retain this exact marker alongside its immutable frame inputs before it suppresses the CPU draw.
 * This narrow candidate supports only an opaque, single-sided SOLID primitive; cutout, translucent, missing, and
 * alpha-bearing material paths remain on the existing CPU route.</p>
 */
final class StaticDirectOpaqueMarker {
    private static final int FULL_BRIGHT_PACKED_LIGHT = 0x00F000F0;
    private final ModelRenderSnapshot exactSnapshot;
    private final X6DrawPrimitive exactDraw;
    private final PreparedRenderPrimitive exactPrimitive;
    private final RenderMaterial exactMaterial;

    private StaticDirectOpaqueMarker(
            ModelRenderSnapshot exactSnapshot,
            X6DrawPrimitive exactDraw,
            PreparedRenderPrimitive exactPrimitive,
            RenderMaterial exactMaterial) {
        this.exactSnapshot = exactSnapshot;
        this.exactDraw = exactDraw;
        this.exactPrimitive = exactPrimitive;
        this.exactMaterial = exactMaterial;
    }

    static StaticDirectOpaqueMarker forExact(ModelRenderSnapshot snapshot, X6DrawPrimitive draw) {
        ModelRenderSnapshot checkedSnapshot = Objects.requireNonNull(snapshot, "snapshot");
        X6DrawPrimitive checkedDraw = Objects.requireNonNull(draw, "draw");
        if (checkedSnapshot.visibility() != RenderVisibility.VISIBLE) {
            throw new IllegalArgumentException("A culled snapshot must not enter the direct-flat candidate");
        }
        if (checkedSnapshot.handle().skinned()) {
            throw new IllegalArgumentException("The direct-flat candidate accepts static/rigid handles only");
        }
        if (!(checkedDraw.binding() instanceof X6GeometryBinding.StaticBinding staticBinding)) {
            throw new IllegalArgumentException("The direct-flat candidate accepts static geometry only");
        }
        PreparedRenderPrimitive primitive = staticBinding.primitive();
        RenderMaterial material = checkedDraw.material();
        if (material != primitive.material()) {
            throw new IllegalArgumentException("The direct-flat candidate requires the primitive's exact material identity");
        }
        if (material.layer() != RenderLayer.SOLID
                || material.doubleSided()
                || material.missingModelMaterial()
                || !opaque(checkedSnapshot.tintArgb())
                || !opaque(material.argbTint())
                || !opaque(checkedDraw.argbTint())) {
            throw new IllegalArgumentException("The direct-flat candidate requires one opaque single-sided SOLID draw");
        }
        if (!isExactNeutralOverlay(checkedSnapshot.packedOverlay())) {
            throw new IllegalArgumentException("The direct-flat candidate requires OverlayTexture.NO_OVERLAY exactly");
        }
        return new StaticDirectOpaqueMarker(checkedSnapshot, checkedDraw, primitive, material);
    }

    static boolean isExactNeutralOverlay(int packedOverlay) {
        return packedOverlay == OverlayTexture.NO_OVERLAY;
    }

    boolean matchesExactly(ModelRenderSnapshot snapshot, X6DrawPrimitive draw) {
        return exactSnapshot == snapshot
                && exactDraw == draw
                && exactDraw.binding() instanceof X6GeometryBinding.StaticBinding staticBinding
                && staticBinding.primitive() == exactPrimitive
                && exactDraw.material() == exactMaterial
                && isExactNeutralOverlay(exactSnapshot.packedOverlay());
    }

    PreparedRenderPrimitive exactPrimitive() {
        return exactPrimitive;
    }

    RenderMaterial exactMaterial() {
        return exactMaterial;
    }

    /** Mirrors the existing rigid CPU emitter's public packed-light selection without approximating either lane. */
    int resolvePackedLight(ModelRenderSnapshot snapshot) {
        if (snapshot != exactSnapshot) {
            throw new IllegalArgumentException("The direct-static marker lost its exact snapshot identity");
        }
        return exactMaterial.emissive() ? FULL_BRIGHT_PACKED_LIGHT : snapshot.packedLight();
    }

    private static boolean opaque(int argb) {
        return (argb >>> 24) == 0xFF;
    }
}
