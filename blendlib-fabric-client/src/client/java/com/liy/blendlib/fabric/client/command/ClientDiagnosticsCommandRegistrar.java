package com.liy.blendlib.fabric.client.command;

import com.liy.blendlib.fabric.client.api.ClientDiagnostic;
import com.liy.blendlib.fabric.client.api.ClientModelView;
import com.liy.blendlib.fabric.client.api.ClientRegistryView;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

/** Registers the real 26.1.2 client-only {@code /blendlib} diagnostics commands. */
public final class ClientDiagnosticsCommandRegistrar {
    private static final ArgumentType<String> MODEL_KEY_ARGUMENT = new ModelKeyArgumentType();

    private ClientDiagnosticsCommandRegistrar() {
    }

    /**
     * Binds {@code /blendlib assets}, {@code /blendlib inspect <model-id>}, and
     * {@code /blendlib diagnostics [model-id]} through Fabric's public client-command callback.
     * This method must be called once by the BlendLib client entrypoint after services initialize.
     */
    public static void register(ClientDiagnosticsCommandRegistry commands) {
        ClientDiagnosticsCommandRegistry checkedCommands = Objects.requireNonNull(commands, "commands");
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, commandBuildContext) -> dispatcher.register(
                ClientCommands.literal("blendlib")
                        .then(ClientCommands.literal("assets")
                                .executes(context -> report(context.getSource(), checkedCommands.execute("assets", List.of()))))
                        .then(ClientCommands.literal("inspect")
                                .then(ClientCommands.argument("model-id", modelKeyArgumentType())
                                        .executes(context -> report(
                                                context.getSource(),
                                                checkedCommands.execute("inspect", List.of(modelArgument(context)))))))
                        .then(ClientCommands.literal("diagnostics")
                                .executes(context -> report(context.getSource(), checkedCommands.execute("diagnostics", List.of())))
                                .then(ClientCommands.argument("model-id", modelKeyArgumentType())
                                        .executes(context -> report(
                                                context.getSource(),
                                                checkedCommands.execute("diagnostics", List.of(modelArgument(context)))))))));
    }

    /**
     * Returns the single-token parser used for client diagnostics model keys.
     *
     * <p>Brigadier's {@code word()} parser rejects the {@code :} separator required by the
     * public {@code namespace:path} key contract. This parser preserves word-token behavior and
     * additionally accepts {@code :} and {@code /}, the latter being valid in BlendLib paths.</p>
     */
    static ArgumentType<String> modelKeyArgumentType() {
        return MODEL_KEY_ARGUMENT;
    }

    private static String modelArgument(CommandContext<FabricClientCommandSource> context) {
        return context.getArgument("model-id", String.class);
    }

    private static int report(FabricClientCommandSource source, ClientDiagnosticsCommandResult result) {
        if (result instanceof ClientDiagnosticsCommandResult.Error error) {
            source.sendError(Component.literal(error.message()));
            return 0;
        }
        if (result instanceof ClientDiagnosticsCommandResult.Assets assets) {
            sendAssets(source, assets.registry());
            return 1;
        }
        if (result instanceof ClientDiagnosticsCommandResult.Inspect inspect) {
            source.sendFeedback(Component.literal(formatModel(inspect.model())));
            return 1;
        }
        ClientDiagnosticsCommandResult.Diagnostics diagnostics = (ClientDiagnosticsCommandResult.Diagnostics) result;
        sendDiagnostics(source, diagnostics.diagnostics());
        return 1;
    }

    private static void sendAssets(FabricClientCommandSource source, ClientRegistryView registry) {
        source.sendFeedback(Component.literal("BlendLib generation=" + registry.generationId()
                + " models=" + registry.models().size()
                + " diagnostics=" + registry.diagnostics().size()));
    }

    private static void sendDiagnostics(FabricClientCommandSource source, List<ClientDiagnostic> diagnostics) {
        if (diagnostics.isEmpty()) {
            source.sendFeedback(Component.literal("BlendLib diagnostics: none"));
            return;
        }
        for (ClientDiagnostic diagnostic : diagnostics) {
            source.sendFeedback(Component.literal(formatDiagnostic(diagnostic)));
        }
    }

    private static String formatModel(ClientModelView model) {
        String diagnostic = model.primaryDiagnostic().map(ClientDiagnosticsCommandRegistrar::formatDiagnostic).orElse("none");
        return "BlendLib model=" + model.key().value()
                + " generation=" + model.generationId()
                + " discovered=" + model.discovered()
                + " missing=" + model.missing()
                + " diagnostic=" + diagnostic;
    }

    private static String formatDiagnostic(ClientDiagnostic diagnostic) {
        return diagnostic.severity() + " " + diagnostic.code()
                + " model=" + String.valueOf(diagnostic.modelKey())
                + " resource=" + String.valueOf(diagnostic.resourceId())
                + " location=" + diagnostic.location()
                + " message=" + diagnostic.message();
    }

    private static final class ModelKeyArgumentType implements ArgumentType<String> {
        @Override
        public String parse(StringReader reader) {
            int start = reader.getCursor();
            while (reader.canRead() && isAllowedInModelKey(reader.peek())) {
                reader.skip();
            }
            return reader.getString().substring(start, reader.getCursor());
        }

        @Override
        public Collection<String> getExamples() {
            return List.of("blendlib_showcase:fixtures/static_model");
        }

        private static boolean isAllowedInModelKey(char character) {
            return StringReader.isAllowedInUnquotedString(character)
                    || character == ':'
                    || character == '/';
        }
    }
}
