package com.liy.blendlib.fabric.client.command;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.fabric.client.api.ClientDiagnostic;
import com.liy.blendlib.fabric.client.api.ClientModelView;
import com.liy.blendlib.fabric.client.api.ClientRegistryView;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable result emitted by the command-neutral client diagnostics command registry. */
public sealed interface ClientDiagnosticsCommandResult
        permits ClientDiagnosticsCommandResult.Assets,
                ClientDiagnosticsCommandResult.Inspect,
                ClientDiagnosticsCommandResult.Diagnostics,
                ClientDiagnosticsCommandResult.Error {
    String command();

    record Assets(ClientRegistryView registry) implements ClientDiagnosticsCommandResult {
        public Assets {
            registry = Objects.requireNonNull(registry, "registry");
        }

        @Override
        public String command() {
            return "assets";
        }
    }

    record Inspect(ClientModelView model) implements ClientDiagnosticsCommandResult {
        public Inspect {
            model = Objects.requireNonNull(model, "model");
        }

        @Override
        public String command() {
            return "inspect";
        }
    }

    record Diagnostics(Optional<BlendModelKey> modelKey, List<ClientDiagnostic> diagnostics)
            implements ClientDiagnosticsCommandResult {
        public Diagnostics {
            modelKey = Objects.requireNonNull(modelKey, "modelKey");
            diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        }

        @Override
        public String command() {
            return "diagnostics";
        }
    }

    record Error(String command, String message) implements ClientDiagnosticsCommandResult {
        public Error {
            command = Objects.requireNonNull(command, "command");
            message = Objects.requireNonNull(message, "message");
        }
    }
}
