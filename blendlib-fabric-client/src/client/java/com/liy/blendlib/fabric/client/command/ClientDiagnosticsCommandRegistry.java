package com.liy.blendlib.fabric.client.command;

import com.liy.blendlib.api.BlendModelKey;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Command-neutral registry for the client diagnostics surface.
 *
 * <p>An entrypoint may bind these literal handlers to a client command dispatcher later. Parsing
 * a model argument always delegates to {@link BlendModelKey#parse(String)}; this registry never
 * resolves a path or reads a resource.</p>
 */
public final class ClientDiagnosticsCommandRegistry {
    private static final List<String> COMMANDS = List.of("assets", "inspect", "diagnostics");
    private final ClientDiagnosticsService diagnostics;

    public ClientDiagnosticsCommandRegistry(ClientDiagnosticsService diagnostics) {
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    /** The immutable set of literals an entrypoint should mount under {@code /blendlib}. */
    public List<String> commandLiterals() {
        return COMMANDS;
    }

    /** Dispatches one literal with its already-tokenized argument list. */
    public ClientDiagnosticsCommandResult execute(String command, List<String> arguments) {
        String checkedCommand = Objects.requireNonNull(command, "command");
        List<String> checkedArguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
        return switch (checkedCommand) {
            case "assets" -> executeAssets(checkedArguments);
            case "inspect" -> executeInspect(checkedArguments);
            case "diagnostics" -> executeDiagnostics(checkedArguments);
            default -> new ClientDiagnosticsCommandResult.Error(
                    checkedCommand, "Unknown BlendLib client diagnostics command: " + checkedCommand);
        };
    }

    private ClientDiagnosticsCommandResult executeAssets(List<String> arguments) {
        if (!arguments.isEmpty()) {
            return argumentCountError("assets", "assets accepts no arguments");
        }
        return new ClientDiagnosticsCommandResult.Assets(diagnostics.assets());
    }

    private ClientDiagnosticsCommandResult executeInspect(List<String> arguments) {
        if (arguments.size() != 1) {
            return argumentCountError("inspect", "inspect requires exactly one <model-id>");
        }
        return parseModelKey("inspect", arguments.getFirst())
                .<ClientDiagnosticsCommandResult>map(key -> new ClientDiagnosticsCommandResult.Inspect(diagnostics.inspect(key)))
                .orElseGet(() -> invalidModelKey("inspect", arguments.getFirst()));
    }

    private ClientDiagnosticsCommandResult executeDiagnostics(List<String> arguments) {
        if (arguments.size() > 1) {
            return argumentCountError("diagnostics", "diagnostics accepts zero or one <model-id>");
        }
        if (arguments.isEmpty()) {
            return new ClientDiagnosticsCommandResult.Diagnostics(Optional.empty(), diagnostics.diagnostics());
        }
        return parseModelKey("diagnostics", arguments.getFirst())
                .<ClientDiagnosticsCommandResult>map(key -> new ClientDiagnosticsCommandResult.Diagnostics(
                        Optional.of(key), diagnostics.diagnostics(key)))
                .orElseGet(() -> invalidModelKey("diagnostics", arguments.getFirst()));
    }

    private static Optional<BlendModelKey> parseModelKey(String command, String rawModelId) {
        try {
            return Optional.of(BlendModelKey.parse(Objects.requireNonNull(rawModelId, "model-id")));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static ClientDiagnosticsCommandResult.Error invalidModelKey(String command, String rawModelId) {
        return new ClientDiagnosticsCommandResult.Error(
                command, "Invalid BlendLib model id: " + String.valueOf(rawModelId));
    }

    private static ClientDiagnosticsCommandResult.Error argumentCountError(String command, String message) {
        return new ClientDiagnosticsCommandResult.Error(command, message);
    }
}
