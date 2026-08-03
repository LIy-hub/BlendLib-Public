package com.liy.blendlib.fabric.client.animation.event;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.AnimationChannel;
import com.liy.blendlib.core.animation.AnimationClip;
import com.liy.blendlib.core.animation.AnimationPath;
import com.liy.blendlib.core.animation.Interpolation;
import com.liy.blendlib.core.animation.runtime.AnimationAdvance;
import com.liy.blendlib.core.animation.runtime.AnimationController;
import com.liy.blendlib.core.animation.runtime.AnimationControllerDefinition;
import com.liy.blendlib.core.animation.runtime.AnimationState;
import com.liy.blendlib.core.animation.runtime.AnimationVisualEvent;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisualEventDispatcherTest {
    private static final BlendAnimationKey IDLE = BlendAnimationKey.parse("blendlib:event_idle");
    private static final AnimationVisualEvent FIRST = new AnimationVisualEvent(
            0.1D, BlendResourceId.parse("blendlib:first"));
    private static final AnimationVisualEvent SECOND = new AnimationVisualEvent(
            0.2D, BlendResourceId.parse("blendlib:second"));
    private static final AnimationVisualEvent THIRD = new AnimationVisualEvent(
            0.3D, BlendResourceId.parse("blendlib:third"));

    @Test
    void dispatchPreservesAdvanceOrderAndForwardsTheExactInstanceKey() {
        VisualEventDispatcher dispatcher = new VisualEventDispatcher();
        BlendInstanceKey firstInstance = BlendInstanceKey.entity("event-session", 41);
        BlendInstanceKey secondInstance = BlendInstanceKey.entity("event-session", 42);
        AnimationAdvance advance = new AnimationAdvance(IDLE, 0.3D, List.of(FIRST, SECOND, THIRD));
        List<ReceivedEvent> received = new ArrayList<>();

        assertEquals(3, dispatcher.dispatch(firstInstance, advance,
                (instanceKey, visualEvent) -> received.add(new ReceivedEvent(instanceKey, visualEvent))));
        assertEquals(3, dispatcher.dispatch(secondInstance, advance,
                (instanceKey, visualEvent) -> received.add(new ReceivedEvent(instanceKey, visualEvent))));

        assertEquals(List.of(
                new ReceivedEvent(firstInstance, FIRST),
                new ReceivedEvent(firstInstance, SECOND),
                new ReceivedEvent(firstInstance, THIRD),
                new ReceivedEvent(secondInstance, FIRST),
                new ReceivedEvent(secondInstance, SECOND),
                new ReceivedEvent(secondInstance, THIRD)
        ), received);
    }

    @Test
    void missingListenerIsASafeNoOp() {
        VisualEventDispatcher dispatcher = new VisualEventDispatcher();
        AnimationAdvance advance = new AnimationAdvance(IDLE, 0.0D, List.of(FIRST));

        assertDoesNotThrow(() -> assertEquals(
                0,
                dispatcher.dispatch(BlendInstanceKey.entity("event-session", 43), advance, null)
        ));
    }

    @Test
    void listenerCannotReturnAStateChangeAndDispatchLeavesControllerUntouched() throws NoSuchMethodException {
        Method callback = VisualEventListener.class.getDeclaredMethod(
                "onVisualEvent", BlendInstanceKey.class, AnimationVisualEvent.class);
        assertEquals(void.class, callback.getReturnType());

        BlendInstanceKey instanceKey = BlendInstanceKey.entity("event-session", 44);
        AnimationController controller = controller(instanceKey);
        AnimationAdvance advance = controller.advance(0.5D);
        BlendAnimationKey stateBeforeDispatch = controller.currentState();
        double timeBeforeDispatch = controller.currentTimeSeconds();
        List<AnimationVisualEvent> received = new ArrayList<>();

        int delivered = new VisualEventDispatcher().dispatch(instanceKey, advance,
                (forwardedInstanceKey, visualEvent) -> {
                    assertEquals(instanceKey, forwardedInstanceKey);
                    received.add(visualEvent);
                });

        assertEquals(1, delivered);
        assertEquals(List.of(FIRST), received);
        assertEquals(stateBeforeDispatch, controller.currentState());
        assertEquals(timeBeforeDispatch, controller.currentTimeSeconds());
        assertTrue(controller.advance(0.0D).visualEvents().isEmpty());
    }

    private static AnimationController controller(BlendInstanceKey instanceKey) {
        AnimationClip clip = new AnimationClip(
                "visual_event_test",
                List.of(new AnimationChannel(
                        0,
                        AnimationPath.TRANSLATION,
                        Interpolation.LINEAR,
                        new float[]{0.0F, 1.0F},
                        new float[]{0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F}
                ))
        );
        AnimationState state = new AnimationState(IDLE, clip, true, 1.0D, 0.0D, null, List.of(FIRST));
        return new AnimationController(instanceKey, new AnimationControllerDefinition(IDLE, Map.of(IDLE, state)));
    }

    private record ReceivedEvent(BlendInstanceKey instanceKey, AnimationVisualEvent visualEvent) {
    }
}
