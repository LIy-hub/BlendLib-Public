package com.liy.blendlib.fabric.v262.showcase;

import com.liy.blendlib.core.model.ModelAsset;
import com.liy.blendlib.fabric.v262.client.V262StaticPreparedHandle;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Objects;
import net.minecraft.client.renderer.SubmitNodeCollector;

/**
 * Minimal 26.2 static consumer that depends on the v1 core asset, not the 26.1.2 runtime adapter JAR.
 *
 * <p>The class is intentionally a narrow showcase seam: load and handle preparation happen before it is called;
 * submit receives only immutable prepared data plus public Minecraft rendering abstractions.</p>
 */
public final class V262StaticShowcase {
    public V262StaticPreparedHandle prepare(ModelAsset asset) {
        return V262StaticPreparedHandle.prepare(Objects.requireNonNull(asset, "asset"));
    }

    public void submit(V262StaticPreparedHandle handle, PoseStack poseStack, SubmitNodeCollector collector) {
        Objects.requireNonNull(handle, "handle").submit(poseStack, collector);
    }
}
