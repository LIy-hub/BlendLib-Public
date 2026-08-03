package com.liy.blendlib.core.descriptor;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.asset.AssetBytes;
import com.liy.blendlib.core.diagnostic.BlendAssetLoadException;
import com.liy.blendlib.core.diagnostic.BlendDiagnostic;
import com.liy.blendlib.core.diagnostic.BlendDiagnosticCodes;
import com.liy.blendlib.core.json.JsonArray;
import com.liy.blendlib.core.json.JsonBoolean;
import com.liy.blendlib.core.json.JsonNumber;
import com.liy.blendlib.core.json.JsonObject;
import com.liy.blendlib.core.json.JsonString;
import com.liy.blendlib.core.json.JsonValue;
import com.liy.blendlib.core.json.StrictJsonParser;
import com.liy.blendlib.core.limits.BlendAssetLimits;
import com.liy.blendlib.core.model.ModelProfile;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Strict decoder for the approved v1 descriptor shape. */
public final class DescriptorDecoder {
    private static final Set<String> TOP_LEVEL_FIELDS = Set.of(
            "format_version", "profile", "mesh", "units_per_block", "materials", "animation", "sockets",
            "extensions_used", "extensions_required", "extensions");
    private static final Set<String> MATERIAL_FIELDS = Set.of("base_color", "mode", "emissive", "double_sided", "cutout_threshold");
    private static final Set<String> ANIMATION_FIELDS = Set.of("initial_state", "states");
    private static final Set<String> STATE_FIELDS = Set.of("clip", "loop", "speed", "blend_seconds", "next", "events");
    private static final Set<String> EVENT_FIELDS = Set.of("time_seconds", "event");
    private static final Set<String> SOCKET_FIELDS = Set.of("node");

    private final BlendAssetLimits limits;

    public DescriptorDecoder() {
        this(BlendAssetLimits.DEFAULT);
    }

    public DescriptorDecoder(BlendAssetLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /** Decodes one descriptor supplied as immutable bytes without performing resource I/O. */
    public ModelDescriptor decode(BlendResourceId modelKey, AssetBytes descriptorBytes) {
        Objects.requireNonNull(modelKey, "modelKey");
        Objects.requireNonNull(descriptorBytes, "descriptorBytes");
        if (descriptorBytes.size() > limits.maxGlbBytes()) {
            throw failure(BlendDiagnosticCodes.LIMIT_001, modelKey, descriptorBytes.resourceId(), "",
                    "Descriptor byte limit exceeded", null);
        }
        JsonObject root;
        try {
            root = object(StrictJsonParser.parse(descriptorBytes.copy()), "", modelKey, descriptorBytes.resourceId());
        } catch (BlendAssetLoadException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw failure(BlendDiagnosticCodes.DESC_002, modelKey, descriptorBytes.resourceId(), "", "Invalid descriptor JSON", exception);
        }

        rejectUnknown(root, TOP_LEVEL_FIELDS, modelKey, descriptorBytes.resourceId(), "");
        int version = integer(required(root, "format_version", "/format_version", modelKey, descriptorBytes.resourceId()),
                "/format_version", modelKey, descriptorBytes.resourceId());
        if (version != 1) {
            throw failure(BlendDiagnosticCodes.DESC_001, modelKey, descriptorBytes.resourceId(), "/format_version",
                    "Only descriptor format_version 1 is supported", null);
        }

        String profileText = string(required(root, "profile", "/profile", modelKey, descriptorBytes.resourceId()),
                "/profile", modelKey, descriptorBytes.resourceId());
        ModelProfile profile;
        try {
            profile = ModelProfile.fromSerializedName(profileText);
        } catch (IllegalArgumentException exception) {
            throw failure(BlendDiagnosticCodes.DESC_002, modelKey, descriptorBytes.resourceId(), "/profile",
                    "Descriptor profile is not supported", exception);
        }

        BlendResourceId meshId = resourceId(
                string(required(root, "mesh", "/mesh", modelKey, descriptorBytes.resourceId()), "/mesh", modelKey,
                        descriptorBytes.resourceId()),
                modelKey, descriptorBytes.resourceId(), "/mesh");
        if (!isModels3dGlb(meshId)) {
            throw failure(BlendDiagnosticCodes.DESC_002, modelKey, descriptorBytes.resourceId(), "/mesh",
                    "Descriptor mesh reference must be a models3d/*.glb resource", null);
        }

        double unitsPerBlock = root.containsKey("units_per_block")
                ? finite(number(root.get("units_per_block"), "/units_per_block", modelKey, descriptorBytes.resourceId()),
                        "/units_per_block", modelKey, descriptorBytes.resourceId(), true)
                : 1.0;
        Map<String, MaterialDefinition> materials = materials(
                object(required(root, "materials", "/materials", modelKey, descriptorBytes.resourceId()), "/materials", modelKey,
                        descriptorBytes.resourceId()),
                modelKey, descriptorBytes.resourceId());
        AnimationDefinition animation = root.containsKey("animation")
                ? animation(object(root.get("animation"), "/animation", modelKey, descriptorBytes.resourceId()), modelKey,
                        descriptorBytes.resourceId())
                : null;
        Map<BlendResourceId, String> sockets = root.containsKey("sockets")
                ? sockets(object(root.get("sockets"), "/sockets", modelKey, descriptorBytes.resourceId()), modelKey,
                        descriptorBytes.resourceId())
                : Map.of();
        List<String> extensionsUsed = root.containsKey("extensions_used")
                ? stringList(array(root.get("extensions_used"), "/extensions_used", modelKey, descriptorBytes.resourceId()),
                        "/extensions_used", modelKey, descriptorBytes.resourceId())
                : List.of();
        if (root.containsKey("extensions_required")) {
            List<String> requiredExtensions = stringList(
                    array(root.get("extensions_required"), "/extensions_required", modelKey, descriptorBytes.resourceId()),
                    "/extensions_required", modelKey, descriptorBytes.resourceId());
            if (!requiredExtensions.isEmpty()) {
                throw failure(BlendDiagnosticCodes.EXT_001, modelKey, descriptorBytes.resourceId(), "/extensions_required",
                        "Descriptor declares an unsupported required extension", null);
            }
        }
        if (root.containsKey("extensions")) {
            object(root.get("extensions"), "/extensions", modelKey, descriptorBytes.resourceId());
        }

        return new ModelDescriptor(descriptorBytes.resourceId(), profile, meshId, unitsPerBlock, materials, animation, sockets,
                extensionsUsed);
    }

    private Map<String, MaterialDefinition> materials(JsonObject object, BlendResourceId modelKey, BlendResourceId resourceId) {
        if (object.size() > limits.maxMaterialSlots()) {
            throw failure(BlendDiagnosticCodes.LIMIT_001, modelKey, resourceId, "/materials", "Material slot limit exceeded", null);
        }
        if (object.size() == 0) {
            throw failure(BlendDiagnosticCodes.DESC_002, modelKey, resourceId, "/materials",
                    "Descriptor materials must not be empty", null);
        }
        Map<String, MaterialDefinition> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonValue> entry : object.values().entrySet()) {
            String pointer = "/materials/" + escape(entry.getKey());
            if (entry.getKey().isBlank()) {
                throw failure(BlendDiagnosticCodes.DESC_002, modelKey, resourceId, pointer, "Material slot name must not be blank", null);
            }
            JsonObject definition = object(entry.getValue(), pointer, modelKey, resourceId);
            rejectUnknown(definition, MATERIAL_FIELDS, modelKey, resourceId, pointer);
            BlendResourceId baseColor = resourceId(
                    string(required(definition, "base_color", pointer + "/base_color", modelKey, resourceId), pointer + "/base_color",
                            modelKey, resourceId),
                    modelKey, resourceId, pointer + "/base_color");
            if (!baseColor.path().startsWith("textures/") || !baseColor.path().endsWith(".png")) {
                throw failure(BlendDiagnosticCodes.DESC_002, modelKey, resourceId, pointer + "/base_color",
                        "Base-color texture must be an external textures/*.png resource", null);
            }
            MaterialDefinition.Mode mode;
            try {
                mode = definition.containsKey("mode")
                        ? MaterialDefinition.Mode.fromSerializedName(string(definition.get("mode"), pointer + "/mode", modelKey, resourceId))
                        : MaterialDefinition.Mode.OPAQUE;
            } catch (IllegalArgumentException exception) {
                throw failure(BlendDiagnosticCodes.DESC_002, modelKey, resourceId, pointer + "/mode",
                        "Material mode is not supported", exception);
            }
            boolean emissive = definition.containsKey("emissive")
                    ? bool(definition.get("emissive"), pointer + "/emissive", modelKey, resourceId)
                    : false;
            boolean doubleSided = definition.containsKey("double_sided")
                    ? bool(definition.get("double_sided"), pointer + "/double_sided", modelKey, resourceId)
                    : false;
            Double cutoutThreshold = definition.containsKey("cutout_threshold")
                    ? finiteRange(number(definition.get("cutout_threshold"), pointer + "/cutout_threshold", modelKey, resourceId),
                            pointer + "/cutout_threshold", modelKey, resourceId, 0.0, 1.0)
                    : null;
            if (cutoutThreshold != null && mode != MaterialDefinition.Mode.CUTOUT) {
                throw failure(BlendDiagnosticCodes.DESC_002, modelKey, resourceId, pointer + "/cutout_threshold",
                        "cutout_threshold is only valid when mode is cutout", null);
            }
            result.put(entry.getKey(), new MaterialDefinition(baseColor, mode, emissive, doubleSided, cutoutThreshold));
        }
        return result;
    }

    private AnimationDefinition animation(JsonObject object, BlendResourceId modelKey, BlendResourceId resourceId) {
        rejectUnknown(object, ANIMATION_FIELDS, modelKey, resourceId, "/animation");
        BlendResourceId initialState = resourceId(string(
                required(object, "initial_state", "/animation/initial_state", modelKey, resourceId), "/animation/initial_state", modelKey,
                resourceId), modelKey, resourceId, "/animation/initial_state");
        JsonObject states = object(required(object, "states", "/animation/states", modelKey, resourceId), "/animation/states", modelKey,
                resourceId);
        if (states.size() == 0) {
            throw failure(BlendDiagnosticCodes.DESC_002, modelKey, resourceId, "/animation/states", "Animation states must not be empty", null);
        }
        if (states.size() > BlendAssetLimits.MAX_ANIMATION_STATES) {
            throw failure(BlendDiagnosticCodes.LIMIT_001, modelKey, resourceId, "/animation/states",
                    "Animation state limit exceeded", null);
        }
        Map<BlendResourceId, AnimationStateDefinition> result = new LinkedHashMap<>();
        int totalEventCount = 0;
        for (Map.Entry<String, JsonValue> entry : states.values().entrySet()) {
            BlendResourceId stateKey = resourceId(entry.getKey(), modelKey, resourceId, "/animation/states/" + escape(entry.getKey()));
            String pointer = "/animation/states/" + escape(entry.getKey());
            JsonObject state = object(entry.getValue(), pointer, modelKey, resourceId);
            rejectUnknown(state, STATE_FIELDS, modelKey, resourceId, pointer);
            String clip = string(required(state, "clip", pointer + "/clip", modelKey, resourceId), pointer + "/clip", modelKey, resourceId);
            boolean loop = bool(required(state, "loop", pointer + "/loop", modelKey, resourceId), pointer + "/loop", modelKey, resourceId);
            double speed = finite(number(required(state, "speed", pointer + "/speed", modelKey, resourceId), pointer + "/speed", modelKey,
                    resourceId), pointer + "/speed", modelKey, resourceId, true);
            if (speed > BlendAssetLimits.MAX_ANIMATION_SPEED) {
                throw failure(BlendDiagnosticCodes.LIMIT_001, modelKey, resourceId, pointer + "/speed",
                        "Animation speed limit exceeded", null);
            }
            double blendSeconds = state.containsKey("blend_seconds")
                    ? finite(number(state.get("blend_seconds"), pointer + "/blend_seconds", modelKey, resourceId), pointer + "/blend_seconds",
                            modelKey, resourceId, false)
                    : 0.0;
            BlendResourceId next = state.containsKey("next")
                    ? resourceId(string(state.get("next"), pointer + "/next", modelKey, resourceId), modelKey, resourceId, pointer + "/next")
                    : null;
            List<AnimationEventDefinition> events = state.containsKey("events")
                    ? events(array(state.get("events"), pointer + "/events", modelKey, resourceId), pointer + "/events", modelKey, resourceId)
                    : List.of();
            totalEventCount += events.size();
            if (totalEventCount > BlendAssetLimits.MAX_VISUAL_EVENTS_PER_DESCRIPTOR) {
                throw failure(BlendDiagnosticCodes.LIMIT_001, modelKey, resourceId, "/animation/states",
                        "Animation descriptor visual-event limit exceeded", null);
            }
            result.put(stateKey, new AnimationStateDefinition(clip, loop, speed, blendSeconds, next, events));
        }
        try {
            return new AnimationDefinition(initialState, result);
        } catch (IllegalArgumentException exception) {
            throw failure(BlendDiagnosticCodes.DESC_002, modelKey, resourceId, "/animation", "Animation state graph is invalid", exception);
        }
    }

    private List<AnimationEventDefinition> events(JsonArray array, String pointer, BlendResourceId modelKey, BlendResourceId resourceId) {
        if (array.size() > BlendAssetLimits.MAX_VISUAL_EVENTS_PER_STATE) {
            throw failure(BlendDiagnosticCodes.LIMIT_001, modelKey, resourceId, pointer,
                    "Animation state visual-event limit exceeded", null);
        }
        List<AnimationEventDefinition> result = new ArrayList<>();
        for (int index = 0; index < array.size(); index++) {
            String itemPointer = pointer + "/" + index;
            JsonObject event = object(array.get(index), itemPointer, modelKey, resourceId);
            rejectUnknown(event, EVENT_FIELDS, modelKey, resourceId, itemPointer);
            double time = finite(number(required(event, "time_seconds", itemPointer + "/time_seconds", modelKey, resourceId),
                    itemPointer + "/time_seconds", modelKey, resourceId), itemPointer + "/time_seconds", modelKey, resourceId, false);
            BlendResourceId eventKey = resourceId(string(required(event, "event", itemPointer + "/event", modelKey, resourceId),
                    itemPointer + "/event", modelKey, resourceId), modelKey, resourceId, itemPointer + "/event");
            result.add(new AnimationEventDefinition(time, eventKey));
        }
        return List.copyOf(result);
    }

    private Map<BlendResourceId, String> sockets(JsonObject object, BlendResourceId modelKey, BlendResourceId resourceId) {
        if (object.size() > limits.maxSockets()) {
            throw failure(BlendDiagnosticCodes.LIMIT_001, modelKey, resourceId, "/sockets", "Socket limit exceeded", null);
        }
        Map<BlendResourceId, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonValue> entry : object.values().entrySet()) {
            String pointer = "/sockets/" + escape(entry.getKey());
            BlendResourceId key = resourceId(entry.getKey(), modelKey, resourceId, pointer);
            JsonObject socket = object(entry.getValue(), pointer, modelKey, resourceId);
            rejectUnknown(socket, SOCKET_FIELDS, modelKey, resourceId, pointer);
            String node = string(required(socket, "node", pointer + "/node", modelKey, resourceId), pointer + "/node", modelKey, resourceId);
            if (node.isBlank() || node.startsWith("/") || node.endsWith("/") || node.contains("//")) {
                throw failure(BlendDiagnosticCodes.DESC_002, modelKey, resourceId, pointer + "/node", "Socket node path is invalid", null);
            }
            result.put(key, node);
        }
        return result;
    }

    private static List<String> stringList(JsonArray array, String pointer, BlendResourceId modelKey, BlendResourceId resourceId) {
        List<String> result = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (int index = 0; index < array.size(); index++) {
            String value = string(array.get(index), pointer + "/" + index, modelKey, resourceId);
            if (!unique.add(value)) {
                throw failure(BlendDiagnosticCodes.DESC_002, modelKey, resourceId, pointer + "/" + index,
                        "Duplicate extension declaration", null);
            }
            result.add(value);
        }
        return List.copyOf(result);
    }

    private static JsonValue required(JsonObject object, String key, String pointer, BlendResourceId modelKey, BlendResourceId resourceId) {
        JsonValue value = object.get(key);
        if (value == null) {
            throw failure(BlendDiagnosticCodes.DESC_002, modelKey, resourceId, pointer, "Required descriptor field is missing", null);
        }
        return value;
    }

    private static void rejectUnknown(
            JsonObject object, Set<String> allowed, BlendResourceId modelKey, BlendResourceId resourceId, String pointer) {
        for (String key : object.values().keySet()) {
            if (!allowed.contains(key)) {
                throw failure(BlendDiagnosticCodes.DESC_002, modelKey, resourceId, pointer + "/" + escape(key),
                        "Unknown descriptor field", null);
            }
        }
    }

    private static JsonObject object(JsonValue value, String pointer, BlendResourceId modelKey, BlendResourceId resourceId) {
        if (value instanceof JsonObject object) {
            return object;
        }
        throw failure(BlendDiagnosticCodes.DESC_002, modelKey, resourceId, pointer, "Expected a JSON object", null);
    }

    private static JsonArray array(JsonValue value, String pointer, BlendResourceId modelKey, BlendResourceId resourceId) {
        if (value instanceof JsonArray array) {
            return array;
        }
        throw failure(BlendDiagnosticCodes.DESC_002, modelKey, resourceId, pointer, "Expected a JSON array", null);
    }

    private static String string(JsonValue value, String pointer, BlendResourceId modelKey, BlendResourceId resourceId) {
        if (value instanceof JsonString string) {
            return string.value();
        }
        throw failure(BlendDiagnosticCodes.DESC_002, modelKey, resourceId, pointer, "Expected a JSON string", null);
    }

    private static boolean bool(JsonValue value, String pointer, BlendResourceId modelKey, BlendResourceId resourceId) {
        if (value instanceof JsonBoolean bool) {
            return bool.value();
        }
        throw failure(BlendDiagnosticCodes.DESC_002, modelKey, resourceId, pointer, "Expected a JSON boolean", null);
    }

    private static JsonNumber number(JsonValue value, String pointer, BlendResourceId modelKey, BlendResourceId resourceId) {
        if (value instanceof JsonNumber number) {
            return number;
        }
        throw failure(BlendDiagnosticCodes.DESC_002, modelKey, resourceId, pointer, "Expected a JSON number", null);
    }

    private static int integer(JsonValue value, String pointer, BlendResourceId modelKey, BlendResourceId resourceId) {
        try {
            return number(value, pointer, modelKey, resourceId).asIntExact();
        } catch (IllegalArgumentException exception) {
            throw failure(BlendDiagnosticCodes.DESC_002, modelKey, resourceId, pointer, "Expected an integer", exception);
        }
    }

    private static double finite(JsonNumber value, String pointer, BlendResourceId modelKey, BlendResourceId resourceId, boolean positive) {
        try {
            double result = value.asDouble();
            if (!Double.isFinite(result) || (positive ? result <= 0.0 : result < 0.0)) {
                throw new IllegalArgumentException("value must be finite and " + (positive ? "positive" : "non-negative"));
            }
            return result;
        } catch (IllegalArgumentException exception) {
            throw failure(BlendDiagnosticCodes.DESC_002, modelKey, resourceId, pointer, "Invalid numeric descriptor value", exception);
        }
    }

    private static double finiteRange(
            JsonNumber value,
            String pointer,
            BlendResourceId modelKey,
            BlendResourceId resourceId,
            double minimum,
            double maximum) {
        try {
            double result = value.asDouble();
            if (!Double.isFinite(result) || result < minimum || result > maximum) {
                throw new IllegalArgumentException("value outside allowed range");
            }
            return result;
        } catch (IllegalArgumentException exception) {
            throw failure(BlendDiagnosticCodes.DESC_002, modelKey, resourceId, pointer, "Invalid numeric descriptor value", exception);
        }
    }

    private static BlendResourceId resourceId(String value, BlendResourceId modelKey, BlendResourceId resourceId, String pointer) {
        try {
            return BlendResourceId.parse(value);
        } catch (IllegalArgumentException exception) {
            throw failure(BlendDiagnosticCodes.DESC_002, modelKey, resourceId, pointer, "Invalid or unsafe resource reference", exception);
        }
    }

    private static boolean isModels3dGlb(BlendResourceId resourceId) {
        String path = resourceId.path();
        String prefix = "models3d/";
        return path.startsWith(prefix) && path.endsWith(".glb") && path.length() > prefix.length() + ".glb".length();
    }

    private static String escape(String key) {
        return key.replace("~", "~0").replace("/", "~1");
    }

    private static BlendAssetLoadException failure(
            String code, BlendResourceId modelKey, BlendResourceId resourceId, String location, String message, Throwable cause) {
        BlendDiagnostic diagnostic = BlendDiagnostic.error(code, modelKey, resourceId, location, message);
        return cause == null ? new BlendAssetLoadException(diagnostic) : new BlendAssetLoadException(diagnostic, cause);
    }
}
