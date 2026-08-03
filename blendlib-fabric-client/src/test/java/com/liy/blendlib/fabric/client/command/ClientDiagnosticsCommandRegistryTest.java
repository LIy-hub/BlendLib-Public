package com.liy.blendlib.fabric.client.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.fabric.client.api.ClientDiagnostic;
import com.liy.blendlib.fabric.client.api.ClientDiagnosticSeverity;
import com.liy.blendlib.fabric.client.api.ClientModelLookup;
import com.liy.blendlib.fabric.client.api.ClientModelView;
import com.liy.blendlib.fabric.client.api.ClientRegistryView;
import com.liy.blendlib.fabric.client.render.MissingModelRenderHandle;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ClientDiagnosticsCommandRegistryTest {
    private static final BlendModelKey KEY = BlendModelKey.parse("command_test:known");
    private static final BlendModelKey UNKNOWN = BlendModelKey.parse("command_test:unknown");

    @Test
    void registryExposesAllThreeCommandLiteralsAndParsesModelKeysThroughPublicKeyContract() {
        ClientDiagnosticsCommandRegistry commands = commands();
        assertEquals(List.of("assets", "inspect", "diagnostics"), commands.commandLiterals());

        ClientDiagnosticsCommandResult inspect = commands.execute("inspect", List.of("command_test:known"));
        ClientDiagnosticsCommandResult.Inspect result = assertInstanceOf(ClientDiagnosticsCommandResult.Inspect.class, inspect);
        assertEquals(KEY, result.model().key());

        ClientDiagnosticsCommandResult invalid = commands.execute("inspect", List.of("command_test:known.json"));
        assertInstanceOf(ClientDiagnosticsCommandResult.Error.class, invalid);
    }

    @Test
    void unknownKeyInspectionAndDiagnosticsExposeStableMissingDiagnostic() {
        ClientDiagnosticsCommandRegistry commands = commands();

        ClientDiagnosticsCommandResult.Inspect inspect = assertInstanceOf(
                ClientDiagnosticsCommandResult.Inspect.class,
                commands.execute("inspect", List.of("command_test:unknown")));
        assertTrue(inspect.model().missing());
        assertFalse(inspect.model().discovered());

        ClientDiagnosticsCommandResult.Diagnostics diagnostics = assertInstanceOf(
                ClientDiagnosticsCommandResult.Diagnostics.class,
                commands.execute("diagnostics", List.of("command_test:unknown")));
        assertEquals(Optional.of(UNKNOWN), diagnostics.modelKey());
        assertEquals("BLENDLIB-DESC-002", diagnostics.diagnostics().getFirst().code());
    }

    @Test
    void argumentCountsAndUnknownLiteralsReturnStructuredErrors() {
        ClientDiagnosticsCommandRegistry commands = commands();
        assertInstanceOf(ClientDiagnosticsCommandResult.Error.class, commands.execute("assets", List.of("extra")));
        assertInstanceOf(ClientDiagnosticsCommandResult.Error.class, commands.execute("diagnostics", List.of("a:b", "c:d")));
        assertInstanceOf(ClientDiagnosticsCommandResult.Error.class, commands.execute("unknown", List.of()));
    }

    private static ClientDiagnosticsCommandRegistry commands() {
        ClientDiagnostic knownDiagnostic = diagnostic(
                "BLENDLIB-TEST-001", KEY, "known fixture diagnostic");
        ClientModelView known = view(KEY, true, Optional.of(knownDiagnostic));
        ClientModelView unknown = view(UNKNOWN, false, Optional.of(diagnostic(
                "BLENDLIB-DESC-002", UNKNOWN, "missing fixture")));
        ClientRegistryView registry = new ClientRegistryView(3L, Map.of(KEY, known), List.of(knownDiagnostic));
        ClientModelLookup lookup = new ClientModelLookup() {
            @Override
            public ClientRegistryView snapshot() {
                return registry;
            }

            @Override
            public ClientModelView resolve(BlendModelKey modelKey) {
                return modelKey.equals(KEY) ? known : unknown;
            }
        };
        return new ClientDiagnosticsCommandRegistry(new ClientDiagnosticsService(lookup));
    }

    private static ClientModelView view(BlendModelKey key, boolean discovered, Optional<ClientDiagnostic> diagnostic) {
        return new ClientModelView(key, 3L, discovered, new MissingModelRenderHandle(key, 3L), diagnostic);
    }

    private static ClientDiagnostic diagnostic(String code, BlendModelKey key, String message) {
        return new ClientDiagnostic(
                ClientDiagnosticSeverity.ERROR,
                code,
                key.resourceId(),
                key.descriptorResourceId(),
                "/",
                message,
                "");
    }
}
