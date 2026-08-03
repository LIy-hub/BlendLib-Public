package com.liy.blendlib.core.tooling;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.asset.AssetBytes;
import com.liy.blendlib.core.descriptor.MaterialDefinition;
import com.liy.blendlib.core.diagnostic.BlendAssetLoadException;
import com.liy.blendlib.core.diagnostic.BlendDiagnostic;
import com.liy.blendlib.core.diagnostic.DiagnosticSeverity;
import com.liy.blendlib.core.json.JsonArray;
import com.liy.blendlib.core.json.JsonBoolean;
import com.liy.blendlib.core.json.JsonNumber;
import com.liy.blendlib.core.json.JsonObject;
import com.liy.blendlib.core.json.JsonString;
import com.liy.blendlib.core.json.JsonValue;
import com.liy.blendlib.core.json.StrictJsonParser;
import com.liy.blendlib.core.loader.ModelAssetLoader;
import com.liy.blendlib.core.limits.BlendAssetLimits;
import com.liy.blendlib.core.model.ModelAsset;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Pure Java, caller-invoked validator for an X5 local export bundle.
 *
 * <p>The service owns neither a watcher nor a runtime reload hook. It validates
 * only caller-provided local files, delegates descriptor/GLB semantics to the
 * existing strict {@link ModelAssetLoader}, and returns deterministic diagnostics.
 * The authoring sidecar is parsed as an authoring-only document; it is never
 * supplied to the runtime loader.</p>
 */
public final class AssetValidatorService {
    private static final String REPORT_FORMAT = "blendlib-x5-asset-report-v1";
    private static final String SIDECAR_FORMAT = "blendlib-x5-authoring-sidecar-v1";
    private static final String SCHEMA_VERSION = "1.0.0";
    private static final int MAX_AUTHORING_FILE_BYTES = 512 * 1024;
    private static final int MAX_AUTHORING_METADATA_ENTRIES = 4_096;
    private static final int MAX_AUTHORING_METADATA_PER_OBJECT = 64;
    private static final int MAX_AUTHORING_METADATA_COMPONENT_LENGTH = 128;
    private static final int MAX_MAPPING_ITEMS = 4_096;
    private static final int MAX_DIAGNOSTICS = 4_096;
    private static final int MAX_TEXT = 1_024;
    private static final int MAX_RUNTIME_FILE_BYTES = BlendAssetLimits.DEFAULT.maxGlbBytes();
    private static final int MAX_TEXTURE_FILE_BYTES = 64 * 1024 * 1024;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern AUTHORING_OBJECT_COMPONENT = Pattern.compile("[a-z0-9_-]+");
    private static final Pattern AUTHORING_KEY_COMPONENT = Pattern.compile("[a-z0-9._/-]+");
    private static final Pattern SECRET_TEXT = Pattern.compile(
            "(?i).*(password|passwd|secret|token|credential|api[_.-]?key|private[ _.-]?key).*");

    private final ModelAssetLoader loader;

    public AssetValidatorService() {
        this(new ModelAssetLoader());
    }

    AssetValidatorService(ModelAssetLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    /** Validates one strict-v1 asset plus its X5 report and authoring sidecar. */
    public AssetValidationResult validate(AssetValidationRequest request) {
        Objects.requireNonNull(request, "request");
        List<AssetValidationDiagnostic> diagnostics = new ArrayList<>();
        Map<String, Long> counts = new LinkedHashMap<>();
        try {
            Path projectRoot = request.projectRoot().toAbsolutePath().normalize();
            if (!Files.isDirectory(projectRoot, LinkOption.NOFOLLOW_LINKS)) {
                throw failure("BLENDLIB-X5-CLI-002", "project_root", "Project root is not an accessible directory.");
            }
            Path realProjectRoot = projectRoot.toRealPath();
            String resourceRoot = safeRelative(request.resourceRoot(), "resource_root");
            String reportPath = safeRelative(request.reportPath(), "report");
            String sidecarPath = safeRelative(request.sidecarPath(), "sidecar");
            String descriptorRelative = resourceRoot + "/" + request.modelKey().namespace() + "/blend_models/"
                    + request.modelKey().path() + ".json";
            BlendResourceId descriptorId = BlendResourceId.of(request.modelKey().namespace(), "blend_models/" + request.modelKey().path() + ".json");
            Path descriptor = requireRegularFile(realProjectRoot, descriptorRelative, "descriptor");
            Path report = requireRegularFile(realProjectRoot, reportPath, "report");
            Path sidecar = requireRegularFile(realProjectRoot, sidecarPath, "sidecar");
            List<Path> runtimeRoots = resolveRuntimeRoots(realProjectRoot, request.runtimeRoots());
            rejectRuntimeTree(report, runtimeRoots, "report");
            rejectRuntimeTree(sidecar, runtimeRoots, "sidecar");

            byte[] descriptorBytes = readBoundedFile(descriptor, MAX_RUNTIME_FILE_BYTES, "BLENDLIB-X5-CLI-003", "descriptor");
            ModelAsset asset;
            try {
                asset = loader.load(request.modelKey(), new AssetBytes(descriptorId, descriptorBytes), resourceId -> {
                    Path resource = requireRegularFile(realProjectRoot,
                            resourceRoot + "/" + resourceId.namespace() + "/" + resourceId.path(), "runtime resource");
                    try {
                        return new AssetBytes(resourceId,
                                readBoundedFile(resource, MAX_RUNTIME_FILE_BYTES, "BLENDLIB-X5-CLI-003", "runtime_resource"));
                    } catch (IOException exception) {
                        throw failure("BLENDLIB-X5-CLI-002", "runtime_resource", "Runtime resource cannot be read under the project root.", exception);
                    }
                });
            } catch (BlendAssetLoadException exception) {
                diagnostics.add(fromCore(exception.diagnostic()));
                return new AssetValidationResult(request.modelKey(), diagnostics, counts);
            }

            Path resourceDirectory = resolveUnder(realProjectRoot, resourceRoot, "resource_root");
            validateRuntimeTextures(asset, request.modelKey(), resourceDirectory, diagnostics, counts);
            JsonObject sidecarDocument = parseObject(sidecar, "sidecar");
            SidecarFacts sidecarFacts = validateSidecar(sidecarDocument, request.modelKey(), asset, diagnostics);
            JsonObject reportDocument = parseObject(report, "report");
            String meshRelative = descriptorMeshRelative(descriptorBytes, resourceRoot);
            List<String> textureRelatives = asset.materials().values().stream()
                    .map(material -> resourceRoot + "/" + material.baseColor().namespace() + "/" + material.baseColor().path())
                    .sorted()
                    .toList();
            validateReport(reportDocument, request, asset, realProjectRoot, descriptorRelative, meshRelative, textureRelatives, sidecarPath,
                    sidecarDocument, sidecarFacts, diagnostics);
            counts.put("animation_clips", (long) asset.clips().size());
            counts.put("materials", (long) asset.materials().size());
            counts.put("nodes", (long) asset.nodes().size());
            counts.put("primitives", (long) asset.primitives().size());
        } catch (ToolingFailure exception) {
            diagnostics.add(new AssetValidationDiagnostic(DiagnosticSeverity.ERROR, exception.code, exception.location, exception.getMessage()));
        } catch (IOException exception) {
            diagnostics.add(new AssetValidationDiagnostic(DiagnosticSeverity.ERROR, "BLENDLIB-X5-CLI-002", "filesystem",
                    "A required local artifact cannot be read."));
        } catch (RuntimeException exception) {
            diagnostics.add(new AssetValidationDiagnostic(DiagnosticSeverity.ERROR, "BLENDLIB-X5-CLI-006", "validation",
                    "Validation input is malformed or exceeds a bounded parser rule."));
        }
        return new AssetValidationResult(request.modelKey(), diagnostics, counts);
    }

    private static void validateRuntimeTextures(
            ModelAsset asset,
            BlendResourceId modelKey,
            Path resourceDirectory,
            List<AssetValidationDiagnostic> diagnostics,
            Map<String, Long> counts) {
        for (Map.Entry<String, MaterialDefinition> entry : new TreeMap<>(asset.materials()).entrySet()) {
            BlendResourceId texture = entry.getValue().baseColor();
            if (!texture.namespace().equals(modelKey.namespace()) || !texture.path().startsWith("textures/") || !texture.path().endsWith(".png")) {
                diagnostics.add(error("BLENDLIB-X5-CLI-003", "material:" + entry.getKey(),
                        "Strict-v1 material texture must be a same-namespace textures/*.png resource."));
                continue;
            }
            Path png = requireRegularFile(resourceDirectory, texture.namespace() + "/" + texture.path(), "texture");
            try {
                byte[] header = readPngHeaderBounded(png);
                if (!hasPngSignature(header)) {
                    diagnostics.add(error("BLENDLIB-X5-CLI-003", "material:" + entry.getKey(),
                            "Material texture is not a valid external PNG file."));
                }
                counts.merge("textures", 1L, Long::sum);
            } catch (IOException exception) {
                throw failure("BLENDLIB-X5-CLI-002", "texture", "Texture cannot be read under the resource root.", exception);
            }
        }
    }

    private static SidecarFacts validateSidecar(
            JsonObject sidecar,
            BlendResourceId modelKey,
            ModelAsset asset,
            List<AssetValidationDiagnostic> diagnostics) {
        requireExactKeys(sidecar, List.of("authoring_metadata", "format", "mapping", "model", "runtime_boundary", "schema_version"),
                "sidecar", diagnostics, "BLENDLIB-X5-CLI-004");
        requireString(sidecar, "format", SIDECAR_FORMAT, "sidecar/format", diagnostics, "BLENDLIB-X5-CLI-004");
        requireString(sidecar, "schema_version", SCHEMA_VERSION, "sidecar/schema_version", diagnostics, "BLENDLIB-X5-CLI-004");
        validateModelObject(valueObject(sidecar, "model", "sidecar/model", diagnostics, "BLENDLIB-X5-CLI-004"), modelKey, asset, "sidecar/model", diagnostics,
                "BLENDLIB-X5-CLI-004");
        validateAuthoringMetadata(valueObject(sidecar, "authoring_metadata", "sidecar/authoring_metadata", diagnostics,
                "BLENDLIB-X5-CLI-004"), diagnostics);
        SidecarFacts facts = validateMapping(valueObject(sidecar, "mapping", "sidecar/mapping", diagnostics,
                "BLENDLIB-X5-CLI-004"), modelKey, asset, diagnostics);
        JsonObject boundary = valueObject(sidecar, "runtime_boundary", "sidecar/runtime_boundary", diagnostics, "BLENDLIB-X5-CLI-004");
        requireExactKeys(boundary, List.of("collision_references_are_authoring_only", "descriptor_extensions_are_not_used",
                "runtime_reads_blend_fbx_obj", "visual_events_are_presentation_only"), "sidecar/runtime_boundary", diagnostics,
                "BLENDLIB-X5-CLI-004");
        requireBoolean(boundary, "collision_references_are_authoring_only", true, "sidecar/runtime_boundary", diagnostics,
                "BLENDLIB-X5-CLI-004");
        requireBoolean(boundary, "descriptor_extensions_are_not_used", true, "sidecar/runtime_boundary", diagnostics, "BLENDLIB-X5-CLI-004");
        requireBoolean(boundary, "runtime_reads_blend_fbx_obj", false, "sidecar/runtime_boundary", diagnostics, "BLENDLIB-X5-CLI-004");
        requireBoolean(boundary, "visual_events_are_presentation_only", true, "sidecar/runtime_boundary", diagnostics,
                "BLENDLIB-X5-CLI-004");
        rejectRuntimeExtensionFields(sidecar, "sidecar", diagnostics, "BLENDLIB-X5-CLI-004");
        rejectHostPaths(sidecar, "sidecar", diagnostics, "BLENDLIB-X5-CLI-004");
        return facts;
    }

    private static void validateReport(
            JsonObject report,
            AssetValidationRequest request,
            ModelAsset asset,
            Path projectRoot,
            String descriptorRelative,
            String meshRelative,
            List<String> textureRelatives,
            String sidecarRelative,
            JsonObject sidecar,
            SidecarFacts sidecarFacts,
            List<AssetValidationDiagnostic> diagnostics) {
        requireExactKeys(report, List.of("artifacts", "counts", "diagnostics", "format", "model", "performance_warnings", "schema_version", "sidecar_sha256"),
                "report", diagnostics, "BLENDLIB-X5-CLI-003");
        requireString(report, "format", REPORT_FORMAT, "report/format", diagnostics, "BLENDLIB-X5-CLI-003");
        requireString(report, "schema_version", SCHEMA_VERSION, "report/schema_version", diagnostics, "BLENDLIB-X5-CLI-003");
        validateModelObject(valueObject(report, "model", "report/model", diagnostics, "BLENDLIB-X5-CLI-003"), request.modelKey(), asset,
                "report/model", diagnostics, "BLENDLIB-X5-CLI-003");
        JsonObject artifacts = valueObject(report, "artifacts", "report/artifacts", diagnostics, "BLENDLIB-X5-CLI-003");
        Map<String, String> required = new TreeMap<>();
        Map<String, Integer> requiredLimits = new TreeMap<>();
        requiredLimits.put(descriptorRelative, MAX_RUNTIME_FILE_BYTES);
        requiredLimits.put(meshRelative, MAX_RUNTIME_FILE_BYTES);
        for (String textureRelative : textureRelatives) {
            requiredLimits.put(textureRelative, MAX_TEXTURE_FILE_BYTES);
        }
        requiredLimits.put(sidecarRelative, MAX_AUTHORING_FILE_BYTES);
        for (Map.Entry<String, Integer> entry : requiredLimits.entrySet()) {
            required.put(entry.getKey(), hashFile(
                    requireRegularFile(projectRoot, entry.getKey(), "required report artifact"), entry.getValue(), "required report artifact"));
        }
        if (!artifacts.values().keySet().equals(required.keySet())) {
            diagnostics.add(error("BLENDLIB-X5-CLI-003", "report/artifacts",
                    "Artifact map must contain exactly the strict runtime bundle and authoring sidecar."));
        }
        for (Map.Entry<String, JsonValue> entry : new TreeMap<>(artifacts.values()).entrySet()) {
            String relative;
            try {
                relative = safeRelative(entry.getKey(), "report artifact");
            } catch (ToolingFailure exception) {
                diagnostics.add(error(exception.code, "report/artifacts", exception.getMessage()));
                continue;
            }
            if (!(entry.getValue() instanceof JsonString digest) || !SHA256.matcher(digest.value()).matches()) {
                diagnostics.add(error("BLENDLIB-X5-CLI-003", "report/artifacts/" + relative, "Artifact hash must be lowercase SHA-256."));
                continue;
            }
            Integer maximumBytes = requiredLimits.get(relative);
            if (maximumBytes == null) {
                diagnostics.add(error("BLENDLIB-X5-CLI-003", "report/artifacts", "Report contains an unexpected artifact key."));
                continue;
            }
            try {
                String actual = hashFile(requireRegularFile(projectRoot, relative, "report artifact"), maximumBytes, "report artifact");
                if (!actual.equals(digest.value())) {
                    diagnostics.add(error("BLENDLIB-X5-CLI-003", "report/artifacts/" + relative, "Artifact hash does not match the local file."));
                }
            } catch (ToolingFailure exception) {
                diagnostics.add(error(exception.code, "report/artifacts/" + relative, exception.getMessage()));
            }
        }
        for (Map.Entry<String, String> entry : required.entrySet()) {
            JsonValue value = artifacts.get(entry.getKey());
            if (!(value instanceof JsonString digest) || !entry.getValue().equals(digest.value())) {
                diagnostics.add(error("BLENDLIB-X5-CLI-003", "report/artifacts", "Report omits or mismatches a required strict-v1 or sidecar artifact."));
            }
        }
        JsonValue sidecarHash = report.get("sidecar_sha256");
        String expectedSidecarHash = sha256(ToolingJson.canonical(sidecar).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (!(sidecarHash instanceof JsonString digest) || !expectedSidecarHash.equals(digest.value())) {
            diagnostics.add(error("BLENDLIB-X5-CLI-003", "report/sidecar_sha256", "Report sidecar hash does not match canonical sidecar JSON."));
        }
        validateReportCounts(valueObject(report, "counts", "report/counts", diagnostics, "BLENDLIB-X5-CLI-003"), asset, sidecarFacts,
                diagnostics);
        JsonArray reportDiagnostics = valueArray(report, "diagnostics", "report/diagnostics", diagnostics, "BLENDLIB-X5-CLI-003",
                MAX_DIAGNOSTICS);
        List<String> warningDocuments = validateReportDiagnostics(reportDiagnostics, diagnostics);
        JsonArray performanceWarnings = valueArray(report, "performance_warnings", "report/performance_warnings", diagnostics,
                "BLENDLIB-X5-CLI-003", MAX_DIAGNOSTICS);
        List<String> actualWarnings = new ArrayList<>();
        Set<String> uniqueWarnings = new HashSet<>();
        for (int index = 0; index < performanceWarnings.size(); index++) {
            JsonValue warning = performanceWarnings.get(index);
            if (!(warning instanceof JsonObject warningObject)) {
                diagnostics.add(error("BLENDLIB-X5-CLI-003", "report/performance_warnings/" + index,
                        "Performance warning must be a diagnostic object."));
                continue;
            }
            String warningLocation = "report/performance_warnings/" + index;
            String severity = validateDiagnosticObject(warningObject, warningLocation, diagnostics);
            if (!"WARN".equals(severity)) {
                diagnostics.add(error("BLENDLIB-X5-CLI-003", warningLocation,
                        "Performance warning entries must have WARN severity."));
            }
            String canonicalWarning = ToolingJson.canonical(warningObject);
            if (!uniqueWarnings.add(canonicalWarning)) {
                diagnostics.add(error("BLENDLIB-X5-CLI-003", warningLocation,
                        "Performance warning entries must not be duplicated."));
            }
            actualWarnings.add(canonicalWarning);
        }
        if (!actualWarnings.equals(warningDocuments)) {
            diagnostics.add(error("BLENDLIB-X5-CLI-003", "report/performance_warnings",
                    "Performance warnings must exactly equal WARN diagnostics in report order."));
        }
        rejectHostPaths(report, "report", diagnostics, "BLENDLIB-X5-CLI-003");
    }

    private static void validateAuthoringMetadata(JsonObject metadata, List<AssetValidationDiagnostic> diagnostics) {
        String code = "BLENDLIB-X5-CLI-004";
        if (metadata.size() > MAX_AUTHORING_METADATA_ENTRIES) {
            diagnostics.add(error(code, "sidecar/authoring_metadata",
                    "Authoring metadata exceeds the bounded entry limit."));
        }
        Map<String, Integer> entriesPerObject = new TreeMap<>();
        int index = 0;
        for (Map.Entry<String, JsonValue> entry : metadata.values().entrySet()) {
            String location = "sidecar/authoring_metadata/entry:" + index++;
            String key = entry.getKey();
            String objectKey = metadataObjectKey(key);
            if (objectKey == null) {
                diagnostics.add(error(code, location, "Authoring metadata key does not use the normalized object.<object>.<key> grammar."));
            } else if (entriesPerObject.merge(objectKey, 1, Integer::sum) > MAX_AUTHORING_METADATA_PER_OBJECT) {
                diagnostics.add(error(code, location, "Authoring metadata exceeds 64 entries for one object."));
            }
            if (looksSecret(key)) {
                diagnostics.add(error(code, location, "Authoring metadata key identifies a secret-bearing field."));
            }
            JsonValue value = entry.getValue();
            if (value instanceof JsonString string) {
                if (string.value().length() > 256 || looksSecret(string.value())) {
                    diagnostics.add(error(code, location, "Authoring metadata text is too long or secret-bearing."));
                }
            } else if (value instanceof JsonNumber number) {
                try {
                    double parsed = new BigDecimal(number.raw()).doubleValue();
                    if (!Double.isFinite(parsed)) {
                        throw new NumberFormatException("non-finite");
                    }
                } catch (NumberFormatException exception) {
                    diagnostics.add(error(code, location, "Authoring metadata number is invalid or non-finite."));
                }
            } else if (!(value instanceof JsonBoolean)) {
                diagnostics.add(error(code, location, "Authoring metadata must contain only bounded scalar values."));
            }
        }
    }

    private static String metadataObjectKey(String key) {
        if (!key.startsWith("object.")) {
            return null;
        }
        int separator = key.indexOf('.', "object.".length());
        if (separator == -1 || separator == "object.".length() || separator == key.length() - 1) {
            return null;
        }
        String objectKey = key.substring("object.".length(), separator);
        String metadataKey = key.substring(separator + 1);
        return normalizedObjectComponent(objectKey) && normalizedAuthoringComponent(metadataKey) ? objectKey : null;
    }

    private static boolean normalizedObjectComponent(String value) {
        return value.length() <= MAX_AUTHORING_METADATA_COMPONENT_LENGTH
                && AUTHORING_OBJECT_COMPONENT.matcher(value).matches()
                && value.equals(value.toLowerCase(Locale.ROOT));
    }

    private static boolean normalizedAuthoringComponent(String value) {
        return value.length() <= MAX_AUTHORING_METADATA_COMPONENT_LENGTH
                && AUTHORING_KEY_COMPONENT.matcher(value).matches()
                && value.equals(value.toLowerCase(Locale.ROOT))
                && !value.contains("..")
                && !value.contains("//");
    }

    private static boolean looksSecret(String value) {
        return SECRET_TEXT.matcher(value).matches();
    }

    private static SidecarFacts validateMapping(
            JsonObject mapping,
            BlendResourceId modelKey,
            ModelAsset asset,
            List<AssetValidationDiagnostic> diagnostics) {
        String code = "BLENDLIB-X5-CLI-004";
        requireExactKeys(mapping, List.of("action_animation_clips", "collection_groups_variants", "collision_references", "empty_sockets",
                "lod_levels", "material_definitions", "timeline_visual_events"), "sidecar/mapping", diagnostics, code);

        JsonArray clips = valueArray(mapping, "action_animation_clips", "sidecar/mapping/action_animation_clips", diagnostics, code,
                MAX_MAPPING_ITEMS);
        Set<String> actualClipNames = new TreeSet<>();
        for (int index = 0; index < clips.size(); index++) {
            String location = "sidecar/mapping/action_animation_clips/" + index;
            JsonObject item = arrayObject(clips, index, location, diagnostics, code);
            requireExactKeys(item, List.of("clip", "frame_end", "frame_start", "source_action"), location, diagnostics, code);
            String clip = boundedString(item, "clip", location + "/clip", diagnostics, code);
            String source = boundedString(item, "source_action", location + "/source_action", diagnostics, code);
            Double start = finiteNumber(item, "frame_start", location + "/frame_start", diagnostics, code);
            Double end = finiteNumber(item, "frame_end", location + "/frame_end", diagnostics, code);
            if (clip != null && !actualClipNames.add(clip)) {
                diagnostics.add(error(code, location + "/clip", "Animation clip mapping is duplicated."));
            }
            if (clip != null && source != null && !clip.equals(source)) {
                diagnostics.add(error(code, location + "/source_action", "Source Action must deterministically match the exported clip name."));
            }
            if (start != null && end != null && end < start) {
                diagnostics.add(error(code, location, "Animation frame range must be ordered."));
            }
        }
        Set<String> expectedClipNames = new TreeSet<>();
        asset.clips().forEach(clip -> expectedClipNames.add(clip.name()));
        if (!actualClipNames.equals(expectedClipNames)) {
            diagnostics.add(error(code, "sidecar/mapping/action_animation_clips",
                    "Animation clip mappings must exactly match runtime GLB clip names."));
        }

        JsonArray groups = valueArray(mapping, "collection_groups_variants", "sidecar/mapping/collection_groups_variants", diagnostics, code,
                MAX_MAPPING_ITEMS);
        Set<String> variants = new HashSet<>();
        for (int index = 0; index < groups.size(); index++) {
            String location = "sidecar/mapping/collection_groups_variants/" + index;
            JsonObject item = arrayObject(groups, index, location, diagnostics, code);
            requireExactKeys(item, List.of("source_collection", "variant_key"), location, diagnostics, code);
            boundedString(item, "source_collection", location + "/source_collection", diagnostics, code);
            String variant = boundedString(item, "variant_key", location + "/variant_key", diagnostics, code);
            if (variant != null && !variants.add(variant)) {
                diagnostics.add(error(code, location + "/variant_key", "Collection variant key is duplicated."));
            }
        }

        JsonArray collisions = valueArray(mapping, "collision_references", "sidecar/mapping/collision_references", diagnostics, code,
                MAX_MAPPING_ITEMS);
        for (int index = 0; index < collisions.size(); index++) {
            String location = "sidecar/mapping/collision_references/" + index;
            JsonObject item = arrayObject(collisions, index, location, diagnostics, code);
            requireExactKeys(item, List.of("authoring_only", "objects", "read_only", "runtime_authority", "source_collection"), location,
                    diagnostics, code);
            requireBoolean(item, "authoring_only", true, location, diagnostics, code);
            requireBoolean(item, "read_only", true, location, diagnostics, code);
            requireString(item, "runtime_authority", "never", location + "/runtime_authority", diagnostics, code);
            boundedString(item, "source_collection", location + "/source_collection", diagnostics, code);
            JsonArray objects = valueArray(item, "objects", location + "/objects", diagnostics, code, MAX_MAPPING_ITEMS);
            for (int objectIndex = 0; objectIndex < objects.size(); objectIndex++) {
                if (!(objects.get(objectIndex) instanceof JsonString string) || string.value().isBlank() || string.value().length() > MAX_TEXT) {
                    diagnostics.add(error(code, location + "/objects/" + objectIndex, "Collision object name must be bounded non-blank text."));
                }
            }
        }

        JsonArray sockets = valueArray(mapping, "empty_sockets", "sidecar/mapping/empty_sockets", diagnostics, code, MAX_MAPPING_ITEMS);
        Set<String> socketKeys = new HashSet<>();
        for (int index = 0; index < sockets.size(); index++) {
            String location = "sidecar/mapping/empty_sockets/" + index;
            JsonObject item = arrayObject(sockets, index, location, diagnostics, code);
            requireExactKeys(item, List.of("key", "source_object"), location, diagnostics, code);
            String key = boundedString(item, "key", location + "/key", diagnostics, code);
            boundedString(item, "source_object", location + "/source_object", diagnostics, code);
            if (key != null) {
                try {
                    BlendResourceId socket = BlendResourceId.parse(key);
                    if (!socket.namespace().equals(modelKey.namespace()) || !socketKeys.add(socket.value())) {
                        diagnostics.add(error(code, location + "/key", "Socket key must be unique and use the model namespace."));
                    }
                } catch (IllegalArgumentException exception) {
                    diagnostics.add(error(code, location + "/key", "Socket key must be a canonical resource id."));
                }
            }
        }

        JsonArray lods = valueArray(mapping, "lod_levels", "sidecar/mapping/lod_levels", diagnostics, code, MAX_MAPPING_ITEMS);
        Set<Long> lodLevels = new HashSet<>();
        for (int index = 0; index < lods.size(); index++) {
            String location = "sidecar/mapping/lod_levels/" + index;
            JsonObject item = arrayObject(lods, index, location, diagnostics, code);
            requireExactKeys(item, List.of("level", "source_collection", "triangle_count"), location, diagnostics, code);
            Long level = nonNegativeInteger(item, "level", location + "/level", diagnostics, code);
            nonNegativeInteger(item, "triangle_count", location + "/triangle_count", diagnostics, code);
            boundedString(item, "source_collection", location + "/source_collection", diagnostics, code);
            if (level != null && !lodLevels.add(level)) {
                diagnostics.add(error(code, location + "/level", "LOD level is duplicated."));
            }
        }

        JsonArray materials = valueArray(mapping, "material_definitions", "sidecar/mapping/material_definitions", diagnostics, code,
                MAX_MAPPING_ITEMS);
        Map<String, String> actualMaterials = new TreeMap<>();
        for (int index = 0; index < materials.size(); index++) {
            String location = "sidecar/mapping/material_definitions/" + index;
            JsonObject item = arrayObject(materials, index, location, diagnostics, code);
            requireExactKeys(item, List.of("authoring_material", "descriptor_mode", "source_material"), location, diagnostics, code);
            String authoring = boundedString(item, "authoring_material", location + "/authoring_material", diagnostics, code);
            String mode = boundedString(item, "descriptor_mode", location + "/descriptor_mode", diagnostics, code);
            String source = boundedString(item, "source_material", location + "/source_material", diagnostics, code);
            if (authoring != null && source != null && !authoring.equals(source)) {
                diagnostics.add(error(code, location + "/source_material", "Source material must deterministically match the descriptor slot name."));
            }
            if (authoring != null && mode != null && actualMaterials.putIfAbsent(authoring, mode) != null) {
                diagnostics.add(error(code, location + "/authoring_material", "Material definition is duplicated."));
            }
        }
        Map<String, String> expectedMaterials = new TreeMap<>();
        asset.materials().forEach((name, material) -> expectedMaterials.put(name, material.mode().serializedName()));
        if (!actualMaterials.equals(expectedMaterials)) {
            diagnostics.add(error(code, "sidecar/mapping/material_definitions",
                    "Material mappings and modes must exactly match the strict runtime descriptor."));
        }

        JsonArray events = valueArray(mapping, "timeline_visual_events", "sidecar/mapping/timeline_visual_events", diagnostics, code,
                MAX_MAPPING_ITEMS);
        Set<String> eventIdentities = new HashSet<>();
        for (int index = 0; index < events.size(); index++) {
            String location = "sidecar/mapping/timeline_visual_events/" + index;
            JsonObject item = arrayObject(events, index, location, diagnostics, code);
            requireExactKeys(item, List.of("event", "frame", "presentation_only", "source_marker"), location, diagnostics, code);
            String event = boundedString(item, "event", location + "/event", diagnostics, code);
            Double frame = finiteNumber(item, "frame", location + "/frame", diagnostics, code);
            requireBoolean(item, "presentation_only", true, location, diagnostics, code);
            boundedString(item, "source_marker", location + "/source_marker", diagnostics, code);
            if (frame != null && frame < 0.0) {
                diagnostics.add(error(code, location + "/frame", "Visual-event frame must be non-negative."));
            }
            if (event != null) {
                try {
                    BlendResourceId eventId = BlendResourceId.parse(event);
                    String identity = event + "@" + (frame == null ? "invalid" : frame);
                    if (!eventId.namespace().equals(modelKey.namespace()) || !eventIdentities.add(identity)) {
                        diagnostics.add(error(code, location + "/event", "Visual event must be unique and use the model namespace."));
                    }
                } catch (IllegalArgumentException exception) {
                    diagnostics.add(error(code, location + "/event", "Visual event must be a canonical resource id."));
                }
            }
        }
        long totalMappingItems = (long) clips.size() + groups.size() + collisions.size() + sockets.size()
                + lods.size() + materials.size() + events.size();
        if (totalMappingItems > MAX_MAPPING_ITEMS) {
            diagnostics.add(error(code, "sidecar/mapping", "Total authoring mapping exceeds 4096 items."));
        }
        return new SidecarFacts(collisions.size(), events.size(), lods.size());
    }

    private static void validateReportCounts(
            JsonObject counts,
            ModelAsset asset,
            SidecarFacts sidecarFacts,
            List<AssetValidationDiagnostic> diagnostics) {
        String code = "BLENDLIB-X5-CLI-003";
        Map<String, Long> expected = new TreeMap<>();
        expected.put("animation_clips", (long) asset.clips().size());
        Set<Integer> joints = new HashSet<>();
        if (asset.skeleton() != null) {
            asset.skeleton().skins().forEach(skin -> joints.addAll(skin.joints()));
        }
        expected.put("bones", (long) joints.size());
        expected.put("collision_references", (long) sidecarFacts.collisionReferences());
        expected.put("events", (long) sidecarFacts.events());
        expected.put("lod_levels", (long) sidecarFacts.lodLevels());
        expected.put("material_slots", (long) asset.materials().size());
        expected.put("triangles", asset.primitives().stream().mapToLong(primitive -> primitive.geometry().indexCount() / 3L).sum());
        expected.put("vertex_weight_records", asset.primitives().stream()
                .mapToLong(primitive -> primitive.geometry().skinned() ? primitive.geometry().vertexCount() : 0L).sum());
        expected.put("vertices", asset.primitives().stream().mapToLong(primitive -> primitive.geometry().vertexCount()).sum());
        requireExactKeys(counts, new ArrayList<>(expected.keySet()), "report/counts", diagnostics, code);
        for (Map.Entry<String, Long> entry : expected.entrySet()) {
            Long actual = nonNegativeInteger(counts, entry.getKey(), "report/counts/" + entry.getKey(), diagnostics, code);
            if (actual != null && !actual.equals(entry.getValue())) {
                diagnostics.add(error(code, "report/counts/" + entry.getKey(),
                        "Report count does not match the strict runtime asset or authoring sidecar."));
            }
        }
    }

    private static List<String> validateReportDiagnostics(JsonArray values, List<AssetValidationDiagnostic> diagnostics) {
        List<String> warnings = new ArrayList<>();
        Set<String> uniqueDiagnostics = new HashSet<>();
        ReportDiagnostic previous = null;
        for (int index = 0; index < values.size(); index++) {
            String location = "report/diagnostics/" + index;
            JsonObject item = arrayObject(values, index, location, diagnostics, "BLENDLIB-X5-CLI-003");
            String severity = validateDiagnosticObject(item, location, diagnostics);
            String canonical = ToolingJson.canonical(item);
            if (!uniqueDiagnostics.add(canonical)) {
                diagnostics.add(error("BLENDLIB-X5-CLI-003", location, "Report diagnostics must not be duplicated."));
            }
            ReportDiagnostic current = reportDiagnostic(item, severity);
            if (current != null && previous != null && compareReportDiagnostics(previous, current) > 0) {
                diagnostics.add(error("BLENDLIB-X5-CLI-003", "report/diagnostics",
                        "Report diagnostics must use canonical severity/code/location/message sort order."));
            }
            if (current != null) {
                previous = current;
            }
            if ("WARN".equals(severity)) {
                warnings.add(canonical);
            }
        }
        return warnings;
    }

    private static ReportDiagnostic reportDiagnostic(JsonObject value, String severity) {
        if (severity == null || !(value.get("code") instanceof JsonString code)
                || !(value.get("location") instanceof JsonString location)
                || !(value.get("message") instanceof JsonString message)) {
            return null;
        }
        return new ReportDiagnostic(severity, code.value(), location.value(), message.value());
    }

    private static int compareReportDiagnostics(ReportDiagnostic left, ReportDiagnostic right) {
        int severity = Integer.compare(severityRank(left.severity()), severityRank(right.severity()));
        if (severity != 0) {
            return severity;
        }
        int code = compareCodePoints(left.code(), right.code());
        if (code != 0) {
            return code;
        }
        int location = compareCodePoints(left.location(), right.location());
        return location != 0 ? location : compareCodePoints(left.message(), right.message());
    }

    private static int severityRank(String severity) {
        return switch (severity) {
            case "ERROR" -> 0;
            case "WARN" -> 1;
            case "INFO" -> 2;
            default -> 3;
        };
    }

    private static int compareCodePoints(String left, String right) {
        int leftIndex = 0;
        int rightIndex = 0;
        while (leftIndex < left.length() && rightIndex < right.length()) {
            int leftPoint = left.codePointAt(leftIndex);
            int rightPoint = right.codePointAt(rightIndex);
            if (leftPoint != rightPoint) {
                return Integer.compare(leftPoint, rightPoint);
            }
            leftIndex += Character.charCount(leftPoint);
            rightIndex += Character.charCount(rightPoint);
        }
        return Integer.compare(left.length() - leftIndex, right.length() - rightIndex);
    }

    private static String validateDiagnosticObject(
            JsonObject diagnostic,
            String location,
            List<AssetValidationDiagnostic> diagnostics) {
        String code = "BLENDLIB-X5-CLI-003";
        requireExactKeys(diagnostic, List.of("code", "location", "message", "remediation", "severity"), location, diagnostics, code);
        String diagnosticCode = boundedString(diagnostic, "code", location + "/code", diagnostics, code);
        String diagnosticLocation = boundedString(diagnostic, "location", location + "/location", diagnostics, code);
        String message = boundedString(diagnostic, "message", location + "/message", diagnostics, code);
        String remediation = boundedString(diagnostic, "remediation", location + "/remediation", diagnostics, code);
        String severity = boundedString(diagnostic, "severity", location + "/severity", diagnostics, code);
        if (diagnosticCode != null && !diagnosticCode.startsWith("BLENDLIB-X5-")) {
            diagnostics.add(error(code, location + "/code", "Report diagnostic code must use the tooling-only BLENDLIB-X5 prefix."));
        }
        if (diagnosticLocation != null && diagnosticLocation.length() > 512) {
            diagnostics.add(error(code, location + "/location", "Report diagnostic location exceeds its bounded length."));
        }
        if (message != null && message.length() > MAX_TEXT || remediation != null && remediation.length() > MAX_TEXT) {
            diagnostics.add(error(code, location, "Report diagnostic text exceeds its bounded length."));
        }
        if (severity != null && !Set.of("ERROR", "WARN", "INFO").contains(severity)) {
            diagnostics.add(error(code, location + "/severity", "Report diagnostic severity is unsupported."));
        }
        return severity;
    }

    private static void validateModelObject(
            JsonObject model,
            BlendResourceId modelKey,
            ModelAsset asset,
            String location,
            List<AssetValidationDiagnostic> diagnostics,
            String code) {
        requireExactKeys(model, List.of("model_id", "namespace", "profile"), location, diagnostics, code);
        requireString(model, "namespace", modelKey.namespace(), location + "/namespace", diagnostics, code);
        requireString(model, "model_id", modelKey.path(), location + "/model_id", diagnostics, code);
        requireString(model, "profile", asset.profile().serializedName(), location + "/profile", diagnostics, code);
    }

    private static JsonObject parseObject(Path path, String location) {
        try {
            byte[] bytes = readBoundedFile(path, MAX_AUTHORING_FILE_BYTES, "BLENDLIB-X5-CLI-003", location);
            JsonValue parsed = StrictJsonParser.parse(bytes);
            if (parsed instanceof JsonObject object) {
                return object;
            }
            throw failure("BLENDLIB-X5-CLI-003", location, "Authoring report or sidecar must be a JSON object.");
        } catch (IOException exception) {
            throw failure("BLENDLIB-X5-CLI-002", location, "Authoring report or sidecar cannot be read.", exception);
        } catch (IllegalArgumentException exception) {
            throw failure("BLENDLIB-X5-CLI-003", location, "Authoring report or sidecar is not strict UTF-8 JSON.", exception);
        }
    }

    private static String descriptorMeshRelative(byte[] descriptorBytes, String resourceRoot) {
        JsonValue parsed = StrictJsonParser.parse(descriptorBytes);
        if (!(parsed instanceof JsonObject descriptor) || !(descriptor.get("mesh") instanceof JsonString mesh)) {
            throw failure("BLENDLIB-X5-CLI-003", "descriptor/mesh", "Strict descriptor does not contain a mesh resource id.");
        }
        try {
            BlendResourceId meshId = BlendResourceId.parse(mesh.value());
            return resourceRoot + "/" + meshId.namespace() + "/" + meshId.path();
        } catch (IllegalArgumentException exception) {
            throw failure("BLENDLIB-X5-CLI-003", "descriptor/mesh", "Strict descriptor mesh resource id is invalid.", exception);
        }
    }

    private static Path requireRegularFile(Path root, String relative, String location) {
        Path target = resolveUnder(root, relative, location);
        try {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw failure("BLENDLIB-X5-CLI-002", location, "A required artifact is missing or is not a regular file.");
            }
            Path real = target.toRealPath();
            if (!real.startsWith(root.toRealPath())) {
                throw failure("BLENDLIB-X5-CLI-001", location, "Artifact resolves outside the authorized project root.");
            }
            return real;
        } catch (IOException exception) {
            throw failure("BLENDLIB-X5-CLI-002", location, "A required artifact cannot be resolved safely.", exception);
        }
    }

    private static List<Path> resolveRuntimeRoots(Path projectRoot, List<String> configuredRoots) {
        List<Path> roots = new ArrayList<>(configuredRoots.size());
        for (String configuredRoot : configuredRoots) {
            Path candidate = resolveUnder(projectRoot, configuredRoot, "runtime_root");
            try {
                Path resolved = Files.exists(candidate) ? candidate.toRealPath() : candidate;
                if (!resolved.startsWith(projectRoot)) {
                    throw failure("BLENDLIB-X5-CLI-001", "runtime_root",
                            "Runtime root resolves outside the authorized project root.");
                }
                if (!roots.contains(resolved)) {
                    roots.add(resolved);
                }
            } catch (IOException exception) {
                throw failure("BLENDLIB-X5-CLI-002", "runtime_root", "Runtime root cannot be resolved safely.", exception);
            }
        }
        return List.copyOf(roots);
    }

    private static void rejectRuntimeTree(Path artifact, List<Path> runtimeRoots, String location) {
        for (Path runtimeRoot : runtimeRoots) {
            if (artifact.equals(runtimeRoot) || artifact.startsWith(runtimeRoot)) {
                throw failure("BLENDLIB-X5-CLI-001", location,
                        "Authoring report and sidecar must remain outside every configured runtime resource tree.");
            }
        }
    }

    private static Path resolveUnder(Path root, String raw, String label) {
        String relative = safeRelative(raw, label);
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) {
            throw failure("BLENDLIB-X5-CLI-001", label, "Path escapes the authorized project root.");
        }
        return target;
    }

    private static String safeRelative(String raw, String label) {
        if (raw == null || raw.isBlank() || !raw.equals(raw.strip()) || raw.indexOf('\\') >= 0 || raw.indexOf(':') >= 0 || raw.indexOf('\u0000') >= 0
                || raw.startsWith("/") || raw.startsWith("~") || raw.contains("://")) {
            throw failure("BLENDLIB-X5-CLI-001", label, "Path must be portable, project-relative, and URI-free.");
        }
        String[] parts = raw.split("/", -1);
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                throw failure("BLENDLIB-X5-CLI-001", label, "Path contains an empty, dot, or traversal segment.");
            }
        }
        return raw;
    }

    private static void requireExactKeys(
            JsonObject object,
            List<String> expected,
            String location,
            List<AssetValidationDiagnostic> diagnostics,
            String code) {
        if (!object.values().keySet().equals(java.util.Set.copyOf(expected))) {
            diagnostics.add(error(code, location, "Object has unknown or missing schema fields."));
        }
    }

    private static JsonObject valueObject(JsonObject parent, String key, String location, List<AssetValidationDiagnostic> diagnostics, String code) {
        JsonValue value = parent.get(key);
        if (value instanceof JsonObject object) {
            return object;
        }
        diagnostics.add(error(code, location, "Expected a JSON object."));
        return new JsonObject(Map.of());
    }

    private static JsonArray valueArray(
            JsonObject parent,
            String key,
            String location,
            List<AssetValidationDiagnostic> diagnostics,
            String code,
            int maximumItems) {
        JsonValue value = parent.get(key);
        if (!(value instanceof JsonArray array)) {
            diagnostics.add(error(code, location, "Expected a JSON array."));
            return new JsonArray(List.of());
        }
        if (array.size() > maximumItems) {
            diagnostics.add(error(code, location, "Array exceeds its bounded item limit."));
            return new JsonArray(array.values().subList(0, maximumItems));
        }
        return array;
    }

    private static JsonObject arrayObject(
            JsonArray values,
            int index,
            String location,
            List<AssetValidationDiagnostic> diagnostics,
            String code) {
        JsonValue value = values.get(index);
        if (value instanceof JsonObject object) {
            return object;
        }
        diagnostics.add(error(code, location, "Expected a JSON object."));
        return new JsonObject(Map.of());
    }

    private static String boundedString(
            JsonObject object,
            String key,
            String location,
            List<AssetValidationDiagnostic> diagnostics,
            String code) {
        JsonValue value = object.get(key);
        if (!(value instanceof JsonString string) || string.value().isBlank() || string.value().length() > MAX_TEXT) {
            diagnostics.add(error(code, location, "Expected bounded non-blank text."));
            return null;
        }
        return string.value();
    }

    private static Double finiteNumber(
            JsonObject object,
            String key,
            String location,
            List<AssetValidationDiagnostic> diagnostics,
            String code) {
        JsonValue value = object.get(key);
        if (!(value instanceof JsonNumber number)) {
            diagnostics.add(error(code, location, "Expected a finite JSON number."));
            return null;
        }
        try {
            double parsed = number.asDouble();
            if (!Double.isFinite(parsed)) {
                throw new NumberFormatException("non-finite");
            }
            return parsed;
        } catch (IllegalArgumentException exception) {
            diagnostics.add(error(code, location, "Expected a finite JSON number."));
            return null;
        }
    }

    private static Long nonNegativeInteger(
            JsonObject object,
            String key,
            String location,
            List<AssetValidationDiagnostic> diagnostics,
            String code) {
        JsonValue value = object.get(key);
        if (!(value instanceof JsonNumber number)) {
            diagnostics.add(error(code, location, "Expected a non-negative integer."));
            return null;
        }
        try {
            long parsed = new BigDecimal(number.raw()).longValueExact();
            if (parsed < 0) {
                throw new ArithmeticException("negative");
            }
            return parsed;
        } catch (ArithmeticException | NumberFormatException exception) {
            diagnostics.add(error(code, location, "Expected a non-negative integer."));
            return null;
        }
    }

    private static void requireString(JsonObject object, String key, String expected, String location,
            List<AssetValidationDiagnostic> diagnostics, String code) {
        if (!(object.get(key) instanceof JsonString value) || !expected.equals(value.value())) {
            diagnostics.add(error(code, location, "String field does not match the deterministic X5 contract."));
        }
    }

    private static void requireBoolean(JsonObject object, String key, boolean expected, String location,
            List<AssetValidationDiagnostic> diagnostics, String code) {
        if (!(object.get(key) instanceof JsonBoolean value) || value.value() != expected) {
            diagnostics.add(error(code, location + "/" + key, "Boolean field does not match the authoring/runtime boundary."));
        }
    }

    private static void rejectRuntimeExtensionFields(JsonValue value, String location, List<AssetValidationDiagnostic> diagnostics, String code) {
        if (value instanceof JsonObject object) {
            for (Map.Entry<String, JsonValue> entry : object.values().entrySet()) {
                if (entry.getKey().equals("extensions") || entry.getKey().equals("extensions_used") || entry.getKey().equals("extensions_required")) {
                    diagnostics.add(error(code, location + "/" + entry.getKey(), "Authoring sidecar must not reuse runtime descriptor extension fields."));
                }
                rejectRuntimeExtensionFields(entry.getValue(), location + "/" + entry.getKey(), diagnostics, code);
            }
        } else if (value instanceof JsonArray array) {
            for (int index = 0; index < array.size(); index++) {
                rejectRuntimeExtensionFields(array.get(index), location + "/" + index, diagnostics, code);
            }
        }
    }

    private static void rejectHostPaths(JsonValue value, String location, List<AssetValidationDiagnostic> diagnostics, String code) {
        if (value instanceof JsonString string && looksHostSpecific(string.value())) {
            diagnostics.add(error(code, location, "Authoring output must not contain a host absolute path or URI."));
        } else if (value instanceof JsonObject object) {
            for (Map.Entry<String, JsonValue> entry : object.values().entrySet()) {
                boolean hostSpecificKey = looksHostSpecific(entry.getKey());
                boolean unsafeKey = hostSpecificKey || looksSecret(entry.getKey());
                String childLocation = location + "/" + (unsafeKey ? "<unsafe-key>" : entry.getKey());
                if (hostSpecificKey) {
                    diagnostics.add(error(code, childLocation,
                            "Authoring output keys must not contain a host absolute path or URI."));
                }
                rejectHostPaths(entry.getValue(), childLocation, diagnostics, code);
            }
        } else if (value instanceof JsonArray array) {
            for (int index = 0; index < array.size(); index++) {
                rejectHostPaths(array.get(index), location + "/" + index, diagnostics, code);
            }
        }
    }

    private static boolean looksHostSpecific(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return value.startsWith("/") || value.startsWith("\\") || value.startsWith("~") || value.matches("^[A-Za-z]:[\\\\/].*")
                || lower.startsWith("file:") || lower.startsWith("http:") || lower.startsWith("https:") || value.contains("://");
    }

    private static boolean hasPngSignature(byte[] bytes) {
        return bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4e && bytes[3] == 0x47
                && bytes[4] == 0x0d && bytes[5] == 0x0a && bytes[6] == 0x1a && bytes[7] == 0x0a;
    }

    private static AssetValidationDiagnostic fromCore(BlendDiagnostic diagnostic) {
        return new AssetValidationDiagnostic(diagnostic.severity(), diagnostic.code(), diagnostic.location(), diagnostic.message());
    }

    private static AssetValidationDiagnostic error(String code, String location, String message) {
        return new AssetValidationDiagnostic(DiagnosticSeverity.ERROR, code, location, message);
    }

    private static byte[] readBoundedFile(Path path, int maximumBytes, String code, String location) throws IOException {
        long declaredSize = boundedFileSize(path, maximumBytes, code, location);
        try (InputStream stream = Files.newInputStream(path)) {
            return readBounded(stream, declaredSize, maximumBytes, code, location);
        }
    }

    static byte[] readBounded(
            InputStream stream,
            long declaredSize,
            int maximumBytes,
            String code,
            String location) throws IOException {
        Objects.requireNonNull(stream, "stream");
        if (declaredSize < 0 || declaredSize > maximumBytes || declaredSize > Integer.MAX_VALUE) {
            throw failure(code, location, "Input exceeds its bounded size.");
        }
        byte[] buffer = new byte[8 * 1024];
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(declaredSize, buffer.length));
        long total = 0;
        while (total < declaredSize) {
            int requested = (int) Math.min(buffer.length, declaredSize - total);
            int count = stream.read(buffer, 0, requested);
            if (count <= 0) {
                throw failure(code, location, "Input changed while being read or exceeds its bounded size.");
            }
            total += count;
            if (total > declaredSize || total > maximumBytes) {
                throw failure(code, location, "Input changed while being read or exceeds its bounded size.");
            }
            output.write(buffer, 0, count);
        }
        if (stream.read(buffer, 0, 1) != -1) {
            throw failure(code, location, "Input changed while being read or exceeds its bounded size.");
        }
        return output.toByteArray();
    }

    private static byte[] readPngHeaderBounded(Path path) throws IOException {
        long declaredSize = boundedFileSize(path, MAX_TEXTURE_FILE_BYTES, "BLENDLIB-X5-CLI-003", "texture");
        byte[] header = new byte[8];
        byte[] buffer = new byte[8 * 1024];
        long total = 0;
        int headerBytes = 0;
        try (InputStream stream = Files.newInputStream(path)) {
            int count;
            while ((count = stream.read(buffer)) != -1) {
                total += count;
                if (total > declaredSize || total > MAX_TEXTURE_FILE_BYTES) {
                    throw failure("BLENDLIB-X5-CLI-003", "texture", "Texture changed while being read or exceeds its bounded size.");
                }
                int copy = Math.min(count, header.length - headerBytes);
                if (copy > 0) {
                    System.arraycopy(buffer, 0, header, headerBytes, copy);
                    headerBytes += copy;
                }
            }
        }
        if (total != declaredSize) {
            throw failure("BLENDLIB-X5-CLI-003", "texture", "Texture changed while being read.");
        }
        return headerBytes == header.length ? header : Arrays.copyOf(header, headerBytes);
    }

    private static long boundedFileSize(Path path, int maximumBytes, String code, String location) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(code, location, "Input is missing or is not a regular file.");
        }
        long size = Files.size(path);
        if (size < 0 || size > maximumBytes) {
            throw failure(code, location, "Input exceeds its bounded size.");
        }
        return size;
    }

    private static String hashFile(Path path, int maximumBytes, String location) {
        try {
            long declaredSize = boundedFileSize(path, maximumBytes, "BLENDLIB-X5-CLI-003", location);
            MessageDigest digest = newSha256();
            byte[] buffer = new byte[8 * 1024];
            long total = 0;
            try (InputStream stream = Files.newInputStream(path)) {
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    total += count;
                    if (total > declaredSize || total > maximumBytes) {
                        throw failure("BLENDLIB-X5-CLI-003", location,
                                "Artifact changed while being hashed or exceeds its bounded size.");
                    }
                    digest.update(buffer, 0, count);
                }
            }
            if (total != declaredSize) {
                throw failure("BLENDLIB-X5-CLI-003", location, "Artifact changed while being hashed.");
            }
            return hex(digest.digest());
        } catch (IOException exception) {
            throw failure("BLENDLIB-X5-CLI-002", location, "Artifact cannot be hashed safely.", exception);
        }
    }

    private static String sha256(byte[] bytes) {
        return hex(newSha256().digest(bytes));
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    private static String hex(byte[] digest) {
        StringBuilder value = new StringBuilder(digest.length * 2);
        for (byte item : digest) {
            value.append(String.format("%02x", item));
        }
        return value.toString();
    }

    private static ToolingFailure failure(String code, String location, String message) {
        return new ToolingFailure(code, location, message, null);
    }

    private static ToolingFailure failure(String code, String location, String message, Throwable cause) {
        return new ToolingFailure(code, location, message, cause);
    }

    private static final class ToolingFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;
        private final String location;

        ToolingFailure(String code, String location, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
            this.location = location;
        }
    }

    private record SidecarFacts(int collisionReferences, int events, int lodLevels) {
    }

    private record ReportDiagnostic(String severity, String code, String location, String message) {
    }
}
