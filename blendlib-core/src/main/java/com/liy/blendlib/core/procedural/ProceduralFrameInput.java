package com.liy.blendlib.core.procedural;

import com.liy.blendlib.api.ModelInstance;
import com.liy.blendlib.core.animation.v2.AnimationV2EvaluationSnapshot;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/** Captures caller input once into bounded immutable lists before X3 evaluation. */
public final class ProceduralFrameInput {
    private final ModelInstance modelInstance;
    private final AnimationV2EvaluationSnapshot evaluation;
    private final List<ProceduralDirective> directives;
    private final List<ProceduralAttachmentDescriptor> attachments;
    private final List<ProceduralVisualEvent> visualEvents;
    private final boolean directiveOverflowOrFailure;
    private final boolean attachmentOverflowOrFailure;
    private final boolean visualEventOverflowOrFailure;

    public ProceduralFrameInput(
            ModelInstance modelInstance,
            AnimationV2EvaluationSnapshot evaluation,
            Iterable<ProceduralDirective> directives,
            Iterable<ProceduralVisualEvent> visualEvents) {
        this(modelInstance, evaluation, directives, List.of(), visualEvents);
    }

    public ProceduralFrameInput(
            ModelInstance modelInstance,
            AnimationV2EvaluationSnapshot evaluation,
            Iterable<ProceduralDirective> directives,
            Iterable<ProceduralAttachmentDescriptor> attachments,
            Iterable<ProceduralVisualEvent> visualEvents) {
        this.modelInstance = Objects.requireNonNull(modelInstance, "modelInstance");
        this.evaluation = Objects.requireNonNull(evaluation, "evaluation");
        Capture<ProceduralDirective> capturedDirectives = capture(directives, ProceduralLimits.MAX_COMMANDS_PER_FRAME);
        Capture<ProceduralAttachmentDescriptor> capturedAttachments = capture(attachments, ProceduralLimits.MAX_ATTACHMENTS);
        Capture<ProceduralVisualEvent> capturedEvents = capture(visualEvents, ProceduralLimits.MAX_VISUAL_EVENTS_PER_FRAME);
        for (ProceduralAttachmentDescriptor attachment : capturedAttachments.values()) {
            if (attachment.payload() instanceof ProceduralAttachmentPayload.ChildModel) {
                throw new IllegalArgumentException(
                        "child-model attachments must be compiled into a frozen ProceduralAttachmentGraph, not supplied per frame");
            }
        }
        this.directives = capturedDirectives.values();
        this.attachments = capturedAttachments.values();
        this.visualEvents = capturedEvents.values();
        this.directiveOverflowOrFailure = capturedDirectives.overflowOrFailure();
        this.attachmentOverflowOrFailure = capturedAttachments.overflowOrFailure();
        this.visualEventOverflowOrFailure = capturedEvents.overflowOrFailure();
    }

    public ModelInstance modelInstance() {
        return modelInstance;
    }

    public AnimationV2EvaluationSnapshot evaluation() {
        return evaluation;
    }

    public List<ProceduralDirective> directives() {
        return directives;
    }

    public List<ProceduralAttachmentDescriptor> attachments() {
        return attachments;
    }

    public List<ProceduralVisualEvent> visualEvents() {
        return visualEvents;
    }

    boolean directiveOverflowOrFailure() {
        return directiveOverflowOrFailure;
    }

    boolean attachmentOverflowOrFailure() {
        return attachmentOverflowOrFailure;
    }

    boolean visualEventOverflowOrFailure() {
        return visualEventOverflowOrFailure;
    }

    private static <T> Capture<T> capture(Iterable<T> iterable, int limit) {
        Objects.requireNonNull(iterable, "iterable");
        List<T> values = new ArrayList<>(limit);
        try {
            Iterator<T> iterator = iterable.iterator();
            while (iterator.hasNext()) {
                if (values.size() >= limit) {
                    return new Capture<>(List.copyOf(values), true);
                }
                values.add(Objects.requireNonNull(iterator.next(), "iterable value"));
            }
            return new Capture<>(List.copyOf(values), false);
        } catch (RuntimeException | AssertionError exception) {
            // Iterables are caller-owned extension input. AssertionError is included deliberately so a hostile
            // iterator cannot escape the bounded-capture boundary or partially publish a frame.
            return new Capture<>(List.copyOf(values), true);
        }
    }

    private record Capture<T>(List<T> values, boolean overflowOrFailure) {
    }
}
