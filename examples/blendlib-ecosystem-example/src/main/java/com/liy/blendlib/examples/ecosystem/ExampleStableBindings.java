package com.liy.blendlib.examples.ecosystem;

import com.liy.blendlib.api.AnimationRequest;
import com.liy.blendlib.api.BlendLib;
import com.liy.blendlib.api.HostRegistrationSpec;
import com.liy.blendlib.examples.ecosystem.blockentity.ExampleBlockEntities;
import com.liy.blendlib.examples.ecosystem.entity.ExampleEntities;
import com.liy.blendlib.examples.ecosystem.item.ExampleItems;
import java.util.ArrayList;
import java.util.List;

/**
 * Stable facade usage for all three host kinds.
 *
 * <p>Calling build creates immutable specifications only. A platform author may submit the
 * corresponding builders after it has installed its one controlled Experimental X4 adapter. This
 * consumer does not seize global adapter ownership from a normal mod entrypoint.</p>
 */
public final class ExampleStableBindings {
    private ExampleStableBindings() {
    }

    /** Returns complete entity, block-entity, and item semantic contracts without platform work. */
    public static List<HostRegistrationSpec<?>> specifications() {
        List<HostRegistrationSpec<?>> specifications = new ArrayList<>();
        specifications.add(BlendLib.entity(ExampleEntities.ANIMATED_ACTOR)
                .model(ExampleKeys.ACTOR_MODEL)
                .animation(host -> AnimationRequest.loop(ExampleKeys.IDLE))
                .build());
        specifications.add(BlendLib.blockEntity(ExampleBlockEntities.ANIMATED_ALTAR)
                .model(ExampleKeys.ACTOR_MODEL)
                .animation(host -> AnimationRequest.loop(ExampleKeys.IDLE))
                .build());
        specifications.add(BlendLib.item(ExampleItems.MODEL_MARKER)
                .model(ExampleKeys.ITEM_MODEL)
                .animation(AnimationRequest.loop(ExampleKeys.IDLE))
                .build());
        return List.copyOf(specifications);
    }
}
