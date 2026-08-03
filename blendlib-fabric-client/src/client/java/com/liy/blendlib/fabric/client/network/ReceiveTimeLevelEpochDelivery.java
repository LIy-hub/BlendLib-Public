package com.liy.blendlib.fabric.client.network;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Client-private deferred-delivery seam that preserves the receive-time level identity. */
final class ReceiveTimeLevelEpochDelivery {
    private ReceiveTimeLevelEpochDelivery() {
    }

    static <L> void defer(
            L receivedLevel,
            Consumer<Runnable> executor,
            Supplier<? extends L> currentLevel,
            Consumer<? super L> delivery) {
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(currentLevel, "currentLevel");
        Objects.requireNonNull(delivery, "delivery");
        if (receivedLevel == null) {
            return;
        }
        executor.accept(() -> {
            if (currentLevel.get() == receivedLevel) {
                delivery.accept(receivedLevel);
            }
        });
    }
}
