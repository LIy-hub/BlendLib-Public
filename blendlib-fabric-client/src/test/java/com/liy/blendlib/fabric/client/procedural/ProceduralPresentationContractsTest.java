package com.liy.blendlib.fabric.client.procedural;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.core.procedural.ProceduralVisualEventType;
import com.liy.blendlib.core.procedural.ResolvedProceduralVisualEvent;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProceduralPresentationContractsTest {
    @Test
    void presentationSeamsConsumeFrozenAttachmentsAndAllSevenTypedEventsWithoutAnAuthorityReturn() throws Exception {
        ProceduralClientTestFixtures.Harness harness = ProceduralClientTestFixtures.presentationHarness();
        try {
            List<ProceduralAttachmentPresentation> attachments = new ArrayList<>();
            new ProceduralAttachmentDispatcher().dispatch(harness.snapshot(), attachments::add);
            assertEquals(List.of(
                            ProceduralClientTestFixtures.id("a-block"),
                            ProceduralClientTestFixtures.id("m-child"),
                            ProceduralClientTestFixtures.id("z-item")),
                    attachments.stream().map(value -> value.attachment().descriptor().id()).toList());

            List<ResolvedProceduralVisualEvent> events = new ArrayList<>();
            new ProceduralVisualEventDispatcher().dispatch(harness.snapshot().visualEvents(), events::add);
            assertEquals(7, events.size());
            assertEquals(java.util.EnumSet.allOf(ProceduralVisualEventType.class), events.stream()
                    .map(value -> value.payload().type())
                    .collect(java.util.stream.Collectors.toCollection(() -> java.util.EnumSet.noneOf(ProceduralVisualEventType.class))));

            Method attachmentDispatch = ProceduralAttachmentDispatcher.class.getDeclaredMethod(
                    "dispatch", com.liy.blendlib.core.procedural.ProceduralFrameSnapshot.class,
                    ProceduralAttachmentPresentationResolver.class);
            Method eventDispatch = ProceduralVisualEventDispatcher.class.getDeclaredMethod(
                    "dispatch", com.liy.blendlib.core.procedural.ProceduralVisualEventBatch.class,
                    ProceduralVisualEventListener.class);
            assertEquals(void.class, attachmentDispatch.getReturnType());
            assertEquals(void.class, eventDispatch.getReturnType());
            assertEquals(void.class, ProceduralAttachmentPresentationResolver.class.getDeclaredMethod(
                    "present", ProceduralAttachmentPresentation.class).getReturnType());
            assertEquals(void.class, ProceduralVisualEventListener.class.getDeclaredMethod(
                    "present", ResolvedProceduralVisualEvent.class).getReturnType());
        } finally {
            harness.plan().attachmentGraph().close();
        }
    }

    @Test
    void presentationCallbackFailuresAreContainedAfterSnapshotPublication() {
        ProceduralClientTestFixtures.Harness harness = ProceduralClientTestFixtures.presentationHarness();
        try {
            List<ResolvedProceduralVisualEvent> originalEvents = harness.snapshot().visualEvents().events();
            int attachmentCount = harness.snapshot().attachments().size();
            AtomicInteger attachmentCalls = new AtomicInteger();
            AtomicInteger eventCalls = new AtomicInteger();

            new ProceduralAttachmentDispatcher().dispatch(harness.snapshot(), attachment -> {
                if (attachmentCalls.getAndIncrement() == 0) {
                    throw new AssertionError("presentation fixture failure");
                }
            });
            new ProceduralVisualEventDispatcher().dispatch(harness.snapshot().visualEvents(), event -> {
                if (eventCalls.getAndIncrement() == 0) {
                    throw new AssertionError("presentation fixture failure");
                }
            });

            assertEquals(attachmentCount, attachmentCalls.get());
            assertEquals(originalEvents.size(), eventCalls.get());
            assertEquals(originalEvents, harness.snapshot().visualEvents().events());
            assertTrue(harness.snapshot().attachments().size() == attachmentCount);
        } finally {
            harness.plan().attachmentGraph().close();
        }
    }
}
