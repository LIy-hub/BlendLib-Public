package com.liy.blendlib.core.tooling;

import com.liy.blendlib.api.BlendResourceId;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Command-line adapter for {@link AssetValidatorService}; X5 supplies opt-in build wiring externally. */
public final class AssetValidatorCli {
    private AssetValidatorCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream output, PrintStream error) {
        try {
            Arguments values = Arguments.parse(args);
            AssetValidationResult result = new AssetValidatorService().validate(values.request());
            if (values.json()) {
                output.println(result.toCanonicalJson());
            } else {
                output.print(result.toText());
            }
            return result.valid() ? AssetValidationExitCode.SUCCESS.code() : AssetValidationExitCode.INVALID_ASSET.code();
        } catch (IllegalArgumentException exception) {
            error.println("BLENDLIB-X5-CLI-USAGE: invalid command-line arguments.");
            error.println(Arguments.usage());
            return AssetValidationExitCode.USAGE.code();
        } catch (RuntimeException exception) {
            error.println("BLENDLIB-X5-CLI-006: validation command failed without publishing a result.");
            return AssetValidationExitCode.UNEXPECTED_FAILURE.code();
        }
    }

    private record Arguments(AssetValidationRequest request, boolean json) {
        static Arguments parse(String[] arguments) {
            Map<String, String> values = new LinkedHashMap<>();
            for (int index = 0; index < arguments.length; index++) {
                String option = arguments[index];
                if ("--help".equals(option)) {
                    throw new IllegalArgumentException("help requested");
                }
                if (!option.startsWith("--") || index + 1 >= arguments.length || values.putIfAbsent(option, arguments[++index]) != null) {
                    throw new IllegalArgumentException("expected each option exactly once as --name value");
                }
            }
            String projectRoot = required(values, "--project-root");
            BlendResourceId modelKey;
            try {
                modelKey = BlendResourceId.parse(required(values, "--model-key"));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("--model-key must be canonical namespace:path", exception);
            }
            String format = values.getOrDefault("--format", "text");
            if (!format.equals("text") && !format.equals("json")) {
                throw new IllegalArgumentException("--format must be text or json");
            }
            String resourceRoot = values.getOrDefault("--resource-root", "src/main/resources/assets");
            String authoringRoot = values.getOrDefault("--authoring-root", "build/blendlib-authoring");
            String report = values.getOrDefault("--report", authoringRoot + "/" + modelKey.namespace() + "/" + modelKey.path() + ".asset-report.json");
            String sidecar = values.getOrDefault("--sidecar", authoringRoot + "/" + modelKey.namespace() + "/" + modelKey.path()
                    + ".blendlib-authoring.json");
            for (String option : values.keySet()) {
                if (!option.equals("--project-root") && !option.equals("--model-key") && !option.equals("--format") && !option.equals("--resource-root")
                        && !option.equals("--authoring-root") && !option.equals("--report") && !option.equals("--sidecar")) {
                    throw new IllegalArgumentException("unknown option " + option);
                }
            }
            return new Arguments(new AssetValidationRequest(Path.of(projectRoot), resourceRoot, modelKey, report, sidecar), format.equals("json"));
        }

        static String usage() {
            return "validateBlendlibAsset --project-root <path> --model-key <namespace:path> [--resource-root <relative>] "
                    + "[--authoring-root <relative>] [--report <relative>] [--sidecar <relative>] [--format text|json]";
        }

        private static String required(Map<String, String> values, String option) {
            String value = values.get(option);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("missing " + option);
            }
            return value;
        }
    }
}
