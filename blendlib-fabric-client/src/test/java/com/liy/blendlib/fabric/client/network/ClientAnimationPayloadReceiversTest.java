package com.liy.blendlib.fabric.client.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ClientAnimationPayloadReceiversTest {
    @Test
    void handlerRegistersBothClientboundPayloadsAndQueuesMutationOnTheClientExecutor() throws IOException {
        Path source = Path.of(System.getProperty("blendlib.projectDir"), "src", "client", "java", "com", "liy",
                "blendlib", "fabric", "client", "network", "ClientAnimationPayloadReceivers.java");
        String text = Files.readString(source);

        assertTrue(text.contains("EntityAnimationPayload.TYPE"));
        assertTrue(text.contains("BlockEntityAnimationPayload.TYPE"));
        assertTrue(text.contains("ClientPlayNetworking.registerGlobalReceiver"));
        assertTrue(text.contains("ReceiveTimeLevelEpochDelivery.defer"));
        assertTrue(text.contains("client::execute"));
        assertTrue(text.contains("runtime.receive"));
        assertFalse(text.contains("Minecraft.getInstance"));
        assertFalse(text.contains("ModelAssetLoader"));
        assertFalse(text.contains("readString("));
    }
}
