package com.liy.blendlib.fixture.localmaven;

import com.liy.blendlib.fabric.client.entity.BlendEntityRendererBuilder;
import com.liy.blendlib.fabric.client.entity.BlendEntityRotation;
import java.util.Objects;
import net.minecraft.world.entity.Entity;

/**
 * Real javac probe for the outer local-Maven adapter's rotation-only entity pose modifier API.
 *
 * <p>The fixture has no project or nested-core dependency. This method is intentionally not run;
 * compiling it proves a separate consumer can configure the callback from the published Maven
 * coordinate alone.</p>
 */
public final class LocalMavenPoseModifierCompileProbe {
    private LocalMavenPoseModifierCompileProbe() {
    }

    public static <E extends Entity> BlendEntityRendererBuilder<E> addTailRotation(
            BlendEntityRendererBuilder<E> animatedBuilder) {
        return Objects.requireNonNull(animatedBuilder, "animatedBuilder")
                .rootRotation((entity, request) -> BlendEntityRotation.IDENTITY)
                .poseModifier((entity, context, basePose) -> {
                    int tailNodeIndex = context.rig().requireNodeIndex("TailBone");
                    BlendEntityRotation baseRotation = basePose.rotation(tailNodeIndex);
                    BlendEntityRotation normalizedOverride = BlendEntityRotation.normalized(
                            baseRotation.x(), baseRotation.y(), baseRotation.z(), baseRotation.w());
                    return basePose.withRotation(tailNodeIndex, normalizedOverride);
                });
    }
}
