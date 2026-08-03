package com.liy.blendlib.fabric.client.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClientDiagnosticsCommandRegistrarTest {
    @Test
    void diagnosticsCommandsDispatchUnquotedNamespacedModelKeys() throws Exception {
        List<String> invocations = new ArrayList<>();
        CommandDispatcher<List<String>> dispatcher = new CommandDispatcher<>();
        dispatcher.register(LiteralArgumentBuilder.<List<String>>literal("blendlib")
                .then(LiteralArgumentBuilder.<List<String>>literal("inspect")
                        .then(RequiredArgumentBuilder.<List<String>, String>argument(
                                        "model-id", ClientDiagnosticsCommandRegistrar.modelKeyArgumentType())
                                .executes(context -> record(context, "inspect"))))
                .then(LiteralArgumentBuilder.<List<String>>literal("diagnostics")
                        .then(RequiredArgumentBuilder.<List<String>, String>argument(
                                        "model-id", ClientDiagnosticsCommandRegistrar.modelKeyArgumentType())
                                .executes(context -> record(context, "diagnostics")))));

        assertEquals(1, dispatcher.execute(
                "blendlib inspect blendlib_showcase:fixtures/static_model", invocations));
        assertEquals(1, dispatcher.execute(
                "blendlib diagnostics blendlib_showcase:fixtures/static_model", invocations));
        assertEquals(List.of(
                "inspect blendlib_showcase:fixtures/static_model",
                "diagnostics blendlib_showcase:fixtures/static_model"), invocations);
    }

    private static int record(com.mojang.brigadier.context.CommandContext<List<String>> context, String command) {
        context.getSource().add(command + " " + context.getArgument("model-id", String.class));
        return 1;
    }
}
