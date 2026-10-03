package com.liy.blendlib.fabric.client.animation.runtime.procedural;

import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.core.animation.runtime.LocalPose;
import com.liy.blendlib.fabric.client.animation.runtime.ClientAnimationPoseContext;
import java.util.List;
import java.util.Objects;

/** Applies components in declaration order, forwarding lifecycle resets to every component. */
public final class ProceduralPosePipeline implements ProceduralPoseComponent {
    private final List<ProceduralPoseComponent> components;

    public ProceduralPosePipeline(List<? extends ProceduralPoseComponent> components) {
        this.components = List.copyOf(components);
    }

    public static ProceduralPosePipeline of(ProceduralPoseComponent... components) {
        return new ProceduralPosePipeline(List.of(components));
    }

    @Override
    public LocalPose modify(ClientAnimationPoseContext context, LocalPose basePose) {
        LocalPose result = Objects.requireNonNull(basePose, "basePose");
        for (ProceduralPoseComponent component : components) {
            result = Objects.requireNonNull(component.modify(context, result), "component result");
        }
        return result;
    }

    @Override public void reset() { components.forEach(ProceduralPoseComponent::reset); }
    @Override public void reset(BlendInstanceKey key) { components.forEach(component -> component.reset(key)); }
}
