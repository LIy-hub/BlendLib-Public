package com.liy.blendlib.datagen;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Internal deterministic pure-Java serializer and output coordinator.
 *
 * <p>All generated paths are constructed from already canonical resource identities, normalized,
 * and then checked against the approved root before the atomic writer is invoked. Descriptor JSON
 * contains only v1-allowed fields; experimental variants and capabilities use separate sidecars.</p>
 */
final class DatagenGenerator {
    private final Path requestedRoot;
    private final int packFormat;
    private final String packDescription;
    private final List<BlendDescriptorSpec> descriptors;

    DatagenGenerator(Path requestedRoot, int packFormat, String packDescription, List<BlendDescriptorSpec> descriptors) {
        this.requestedRoot = Objects.requireNonNull(requestedRoot, "requestedRoot");
        this.packFormat = packFormat;
        this.packDescription = Objects.requireNonNull(packDescription, "packDescription");
        this.descriptors = List.copyOf(Objects.requireNonNull(descriptors, "descriptors"));
        DatagenLimits.requireAtMost(
                "descriptor specifications", this.descriptors.size(), DatagenLimits.MAX_DESCRIPTOR_SPECS);
    }

    DatagenOutput generate() {
        Path root = requestedRoot.toAbsolutePath().normalize();
        TreeMap<String, OutputFile> outputs = new TreeMap<>();
        add(outputs, root, "pack.mcmeta", packSkeletonJson());
        descriptors.stream()
                .sorted(Comparator.comparing(spec -> spec.modelKey().value()))
                .forEach(specification -> addDescriptorOutputs(outputs, root, specification));
        DatagenLimits.validateSerializedOutputs(outputs);
        List<Path> files = new ArrayList<>();
        for (OutputFile output : outputs.values()) {
            AtomicUtf8Writer.write(root, output.path(), output.content());
            files.add(output.path());
        }
        return new DatagenOutput(files);
    }

    private void addDescriptorOutputs(
            Map<String, OutputFile> outputs,
            Path root,
            BlendDescriptorSpec specification) {
        BlendModelKey key = specification.modelKey();
        add(outputs, root, assetPath(key, "blend_models"), descriptorJson(specification));
        if (!specification.variants().isEmpty()) {
            add(outputs, root, assetPath(key, "blendlib_variants"), variantsJson(specification));
        }
        if (!specification.capabilities().isEmpty()) {
            add(outputs, root, assetPath(key, "blendlib_capabilities"), capabilitiesJson(specification));
        }
    }

    private static String assetPath(BlendModelKey key, String directory) {
        return "assets/" + key.namespace() + "/" + directory + "/" + key.path() + ".json";
    }

    private static void add(Map<String, OutputFile> outputs, Path root, String relativePath, String content) {
        Path target = resolveUnderRoot(root, relativePath);
        OutputFile previous = outputs.putIfAbsent(relativePath, new OutputFile(target, content));
        if (previous != null) {
            throw new DatagenException(DatagenDiagnosticCode.DUPLICATE_OUTPUT,
                    "Multiple declarations target generated output " + relativePath);
        }
    }

    private static Path resolveUnderRoot(Path root, String relativePath) {
        if (relativePath.isBlank() || relativePath.startsWith("/") || relativePath.startsWith("\\")) {
            throw new DatagenException(DatagenDiagnosticCode.PATH_TRAVERSAL,
                    "Generated relative path is not a safe relative path: " + relativePath);
        }
        Path candidate = root.resolve(relativePath).normalize().toAbsolutePath();
        if (!candidate.startsWith(root)) {
            throw new DatagenException(DatagenDiagnosticCode.PATH_TRAVERSAL,
                    "Generated path escapes output root: " + relativePath);
        }
        return candidate;
    }

    private String packSkeletonJson() {
        return "{\"pack\":{\"pack_format\":" + packFormat
                + ",\"description\":" + Json.text(packDescription) + "}}\n";
    }

    private static String descriptorJson(BlendDescriptorSpec specification) {
        StringBuilder json = new StringBuilder(512);
        json.append('{');
        property(json, "format_version", "1");
        property(json, "profile", Json.text(specification.profile().serializedName()));
        property(json, "mesh", Json.text(specification.mesh().value()));
        property(json, "units_per_block", number(specification.unitsPerBlock()));
        property(json, "materials", materialsJson(specification.materials()));
        specification.animation().ifPresent(animation -> property(json, "animation", animationJson(animation)));
        if (!specification.sockets().isEmpty()) {
            property(json, "sockets", socketsJson(specification.sockets()));
        }
        removeTrailingSeparator(json);
        json.append("}\n");
        return json.toString();
    }

    private static String materialsJson(Map<String, DatagenMaterial> materials) {
        StringBuilder json = new StringBuilder("{");
        materials.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            json.append(Json.text(entry.getKey())).append(':');
            DatagenMaterial material = entry.getValue();
            json.append('{');
            property(json, "base_color", Json.text(material.baseColor().value()));
            property(json, "mode", Json.text(material.mode().serializedName()));
            property(json, "emissive", Boolean.toString(material.emissive()));
            property(json, "double_sided", Boolean.toString(material.doubleSided()));
            material.cutoutThreshold().ifPresent(value -> property(json, "cutout_threshold", number(value)));
            removeTrailingSeparator(json);
            json.append("},");
        });
        removeTrailingSeparator(json);
        return json.append('}').toString();
    }

    private static String animationJson(DatagenAnimationGraph animation) {
        StringBuilder json = new StringBuilder("{");
        property(json, "initial_state", Json.text(animation.initialState().value()));
        json.append(Json.text("states")).append(":{");
        animation.states().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(BlendResourceId::value)))
                .forEach(entry -> {
                    json.append(Json.text(entry.getKey().value())).append(':');
                    appendAnimationState(json, entry.getValue());
                    json.append(',');
                });
        removeTrailingSeparator(json);
        json.append("},");
        removeTrailingSeparator(json);
        return json.append('}').toString();
    }

    private static void appendAnimationState(StringBuilder json, DatagenAnimationState state) {
        json.append('{');
        property(json, "clip", Json.text(state.clip()));
        property(json, "loop", Boolean.toString(state.loop()));
        property(json, "speed", number(state.speed()));
        if (state.blendSeconds() != 0.0) {
            property(json, "blend_seconds", number(state.blendSeconds()));
        }
        state.next().ifPresent(next -> property(json, "next", Json.text(next.value())));
        if (!state.events().isEmpty()) {
            json.append(Json.text("events")).append(":[");
            state.events().stream()
                    .sorted(Comparator.comparingDouble(DatagenAnimationEvent::timeSeconds)
                            .thenComparing(event -> event.eventId().value()))
                    .forEach(event -> json.append("{\"time_seconds\":")
                            .append(number(event.timeSeconds()))
                            .append(",\"event\":")
                            .append(Json.text(event.eventId().value()))
                            .append("},"));
            removeTrailingSeparator(json);
            json.append("],");
        }
        removeTrailingSeparator(json);
        json.append('}');
    }

    private static String socketsJson(List<DatagenSocket> sockets) {
        StringBuilder json = new StringBuilder("{");
        sockets.stream().sorted(Comparator.comparing(socket -> socket.socketId().value())).forEach(socket -> json
                .append(Json.text(socket.socketId().value()))
                .append(":{\"node\":")
                .append(Json.text(socket.nodePath()))
                .append("},"));
        removeTrailingSeparator(json);
        return json.append('}').toString();
    }

    private static String variantsJson(BlendDescriptorSpec specification) {
        StringBuilder json = new StringBuilder("{\"format_version\":1,\"model\":");
        json.append(Json.text(specification.modelKey().value())).append(",\"variants\":[");
        specification.variants().stream().sorted(Comparator.comparing(variant -> variant.variantId().value())).forEach(variant -> {
            json.append("{\"id\":").append(Json.text(variant.variantId().value())).append(",\"selectors\":{");
            variant.selectors().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> json
                    .append(Json.text(entry.getKey())).append(':').append(Json.text(entry.getValue())).append(','));
            removeTrailingSeparator(json);
            json.append("}},");
        });
        removeTrailingSeparator(json);
        return json.append("]}\n").toString();
    }

    private static String capabilitiesJson(BlendDescriptorSpec specification) {
        StringBuilder json = new StringBuilder("{\"format_version\":1,\"model\":");
        json.append(Json.text(specification.modelKey().value())).append(",\"capabilities\":[");
        specification.capabilities().stream()
                .sorted(Comparator.comparing(capability -> capability.capabilityId().value()))
                .forEach(capability -> {
                    json.append("{\"id\":").append(Json.text(capability.capabilityId().value()))
                            .append(",\"requirement\":")
                            .append(Json.text(capability.requirement().name().toLowerCase(java.util.Locale.ROOT)));
                    capability.fallbackId().ifPresent(fallback -> json.append(",\"fallback\":")
                            .append(Json.text(fallback.value())));
                    json.append("},");
                });
        removeTrailingSeparator(json);
        return json.append("]}\n").toString();
    }

    private static void property(StringBuilder json, String name, String value) {
        json.append(Json.text(name)).append(':').append(value).append(',');
    }

    private static void removeTrailingSeparator(StringBuilder json) {
        int length = json.length();
        if (length > 0 && json.charAt(length - 1) == ',') {
            json.setLength(length - 1);
        }
    }

    private static String number(double value) {
        if (!Double.isFinite(value)) {
            throw new DatagenException(DatagenDiagnosticCode.INVALID_SPECIFICATION,
                    "Generated numeric values must be finite");
        }
        return Double.toString(value);
    }

    static record OutputFile(Path path, String content) {
        OutputFile {
            path = Objects.requireNonNull(path, "path");
            content = Objects.requireNonNull(content, "content");
        }
    }

    private static final class Json {
        private Json() {
        }

        private static String text(String value) {
            String checked = Objects.requireNonNull(value, "value");
            StringBuilder json = new StringBuilder(checked.length() + 2).append('"');
            for (int offset = 0; offset < checked.length(); ) {
                int codePoint = checked.codePointAt(offset);
                offset += Character.charCount(codePoint);
                switch (codePoint) {
                    case '"' -> json.append("\\\"");
                    case '\\' -> json.append("\\\\");
                    case '\b' -> json.append("\\b");
                    case '\f' -> json.append("\\f");
                    case '\n' -> json.append("\\n");
                    case '\r' -> json.append("\\r");
                    case '\t' -> json.append("\\t");
                    default -> {
                        if (codePoint < 0x20) {
                            json.append(String.format(java.util.Locale.ROOT, "\\u%04x", codePoint));
                        } else {
                            json.appendCodePoint(codePoint);
                        }
                    }
                }
            }
            return json.append('"').toString();
        }
    }
}
