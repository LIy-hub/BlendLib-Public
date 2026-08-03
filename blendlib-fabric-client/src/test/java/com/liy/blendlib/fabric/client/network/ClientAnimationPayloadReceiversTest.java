package com.liy.blendlib.fabric.client.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ClientAnimationPayloadReceiversTest {
    @Test
    void sameReceiveTimeLevelDeliversEntityAndBlockEntityCallbacks() {
        Object levelA = new Object();
        AtomicReference<Object> currentLevel = new AtomicReference<>(levelA);
        ArrayDeque<Runnable> callbacks = new ArrayDeque<>();
        List<Delivery> deliveries = new ArrayList<>();

        defer("entity", levelA, callbacks, currentLevel::get, deliveries);
        defer("block_entity", levelA, callbacks, currentLevel::get, deliveries);
        runAll(callbacks);

        assertEquals(List.of(new Delivery("entity", levelA), new Delivery("block_entity", levelA)), deliveries);
    }

    @Test
    void replacingLevelBeforeExecutionDropsEntityAndBlockEntityCallbacks() {
        Object levelA = new Object();
        AtomicReference<Object> currentLevel = new AtomicReference<>(levelA);
        ArrayDeque<Runnable> callbacks = new ArrayDeque<>();
        List<Delivery> deliveries = new ArrayList<>();

        defer("entity", levelA, callbacks, currentLevel::get, deliveries);
        defer("block_entity", levelA, callbacks, currentLevel::get, deliveries);
        currentLevel.set(new Object());
        runAll(callbacks);

        assertTrue(deliveries.isEmpty());
    }

    @Test
    void clearingLevelBeforeExecutionDropsEntityAndBlockEntityCallbacks() {
        Object levelA = new Object();
        AtomicReference<Object> currentLevel = new AtomicReference<>(levelA);
        ArrayDeque<Runnable> callbacks = new ArrayDeque<>();
        List<Delivery> deliveries = new ArrayList<>();

        defer("entity", levelA, callbacks, currentLevel::get, deliveries);
        defer("block_entity", levelA, callbacks, currentLevel::get, deliveries);
        currentLevel.set(null);
        runAll(callbacks);

        assertTrue(deliveries.isEmpty());
    }

    @Test
    void disconnectBeforeExecutionDropsEntityAndBlockEntityCallbacks() {
        Object levelA = new Object();
        AtomicReference<Object> retainedLevel = new AtomicReference<>(levelA);
        AtomicBoolean connected = new AtomicBoolean(true);
        ArrayDeque<Runnable> callbacks = new ArrayDeque<>();
        List<Delivery> deliveries = new ArrayList<>();

        defer("entity", levelA, callbacks, () -> connected.get() ? retainedLevel.get() : null, deliveries);
        defer("block_entity", levelA, callbacks, () -> connected.get() ? retainedLevel.get() : null, deliveries);
        connected.set(false);
        runAll(callbacks);

        assertTrue(deliveries.isEmpty());
    }

    @Test
    void nullReceiveTimeLevelIsDroppedWithoutScheduling() {
        ArrayDeque<Runnable> callbacks = new ArrayDeque<>();
        List<Delivery> deliveries = new ArrayList<>();

        defer("entity", null, callbacks, () -> null, deliveries);
        defer("block_entity", null, callbacks, () -> null, deliveries);

        assertTrue(callbacks.isEmpty());
        assertTrue(deliveries.isEmpty());
    }

    @Test
    void handlerRegistersBothPayloadsAndUsesOnlyThePrivateEpochSeam() throws IOException {
        Path source = Path.of(System.getProperty("blendlib.projectDir"), "src", "client", "java", "com", "liy",
                "blendlib", "fabric", "client", "network", "ClientAnimationPayloadReceivers.java");
        String text = Files.readString(source);

        assertTrue(text.contains("EntityAnimationPayload.TYPE"));
        assertTrue(text.contains("BlockEntityAnimationPayload.TYPE"));
        assertTrue(text.contains("ClientPlayNetworking.registerGlobalReceiver"));
        assertEquals(2, text.split("ReceiveTimeLevelEpochDelivery.defer", -1).length - 1);
        assertTrue(text.contains("ClientLevel receivedLevel = client.level"));
        assertTrue(text.contains("() -> client.level"));
        assertTrue(text.contains("runtime.receive"));
        assertFalse(text.contains("Minecraft.getInstance"));
        assertFalse(text.contains("ModelAssetLoader"));
        assertFalse(text.contains("readString("));
        assertFalse(text.contains("Unsafe"));
        assertFalse(text.contains("reflect"));
    }

    private static void defer(
            String targetKind,
            Object receivedLevel,
            ArrayDeque<Runnable> callbacks,
            Supplier<Object> currentLevel,
            List<Delivery> deliveries) {
        ReceiveTimeLevelEpochDelivery.defer(
                receivedLevel,
                callbacks::addLast,
                currentLevel,
                level -> deliveries.add(new Delivery(targetKind, level)));
    }

    private static void runAll(ArrayDeque<Runnable> callbacks) {
        while (!callbacks.isEmpty()) {
            callbacks.removeFirst().run();
        }
    }

    private record Delivery(String targetKind, Object level) {
    }
}
