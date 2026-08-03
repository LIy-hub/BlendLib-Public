package com.liy.blendlib.core.profile.experimental;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.asset.AssetBytes;
import com.liy.blendlib.core.json.JsonArray;
import com.liy.blendlib.core.json.JsonBoolean;
import com.liy.blendlib.core.json.JsonNumber;
import com.liy.blendlib.core.json.JsonObject;
import com.liy.blendlib.core.json.JsonString;
import com.liy.blendlib.core.json.JsonValue;
import com.liy.blendlib.core.json.StrictJsonParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict parser for the new X9 descriptor schema; it never accepts a v1 descriptor. */
public final class ExperimentalDescriptorDecoder {
    private static final Set<String> TOP_LEVEL_FIELDS = Set.of(
            "format_version", "profile", "mesh", "units_per_block", "materials", "capabilities");
    private static final Set<String> MATERIAL_FIELDS = Set.of(
            "base_color", "mode", "double_sided", "metallic_factor", "roughness_factor", "normal_texture",
            "occlusion_texture", "emissive_texture", "emissive_factor", "alpha_cutoff");
    private static final Set<String> CAPABILITY_FIELDS = Set.of("requirement", "min_version", "max_version", "fallback");
    private static final Pattern MATERIAL_SLOT_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    private final ExperimentalProfileLimits limits;

    public ExperimentalDescriptorDecoder() {
        this(ExperimentalProfileLimits.DEFAULT);
    }

    public ExperimentalDescriptorDecoder(ExperimentalProfileLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /** Decodes caller-supplied bytes only; it performs no resource resolution or runtime I/O. */
    public ExperimentalDescriptor decode(BlendResourceId modelKey, AssetBytes descriptorBytes) {
        Objects.requireNonNull(modelKey, "modelKey");
        Objects.requireNonNull(descriptorBytes, "descriptorBytes");
        if (descriptorBytes.size() > limits.maxDescriptorBytes()) {
            throw fail("BLENDLIB-X9-LIMIT-001", "", "X9 descriptor byte limit exceeded", "");
        }

        JsonObject root;
        try {
            root = object(StrictJsonParser.parse(descriptorBytes.copy()), "");
        } catch (ExperimentalProfileValidationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw fail("BLENDLIB-X9-DESC-002", "", "X9 descriptor JSON is invalid", "");
        }
        rejectUnknown(root, TOP_LEVEL_FIELDS, "");
        int formatVersion = integer(required(root, "format_version", "/format_version"), "/format_version");
        if (formatVersion != 2) {
            throw fail("BLENDLIB-X9-DESC-001", "/format_version", "X9 requires descriptor format_version 2", "");
        }
        ExperimentalProfile profile;
        try {
            profile = ExperimentalProfile.fromSerializedName(string(required(root, "profile", "/profile"), "/profile"));
        } catch (IllegalArgumentException exception) {
            throw fail("BLENDLIB-X9-DESC-002", "/profile", "Unsupported X9 experimental profile", "");
        }
        BlendResourceId meshId = resourceId(string(required(root, "mesh", "/mesh"), "/mesh"), "/mesh");
        if (!isModels3dGlb(meshId)) {
            throw fail("BLENDLIB-X9-DESC-002", "/mesh", "X9 mesh must be a models3d/*.glb resource", "");
        }
        double unitsPerBlock = root.containsKey("units_per_block")
                ? finitePositive(number(root.get("units_per_block"), "/units_per_block"), "/units_per_block")
                : 1.0;
        if (unitsPerBlock > 64.0) {
            throw fail("BLENDLIB-X9-LIMIT-001", "/units_per_block", "units_per_block exceeds the X9 bound", "");
        }
        Map<String, ExperimentalMaterialDefinition> materials = materials(
                object(required(root, "materials", "/materials"), "/materials"));
        CapabilityLists capabilities = capabilities(object(required(root, "capabilities", "/capabilities"), "/capabilities"));
        return new ExperimentalDescriptor(descriptorBytes.resourceId(), profile, meshId, unitsPerBlock, materials,
                capabilities.required(), capabilities.optional());
    }

    private Map<String, ExperimentalMaterialDefinition> materials(JsonObject object) {
        if (object.size() == 0) {
            throw fail("BLENDLIB-X9-DESC-002", "/materials", "X9 materials must not be empty", "");
        }
        if (object.size() > limits.maxMaterials()) {
            throw fail("BLENDLIB-X9-LIMIT-001", "/materials", "X9 material limit exceeded", "");
        }
        Map<String, ExperimentalMaterialDefinition> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonValue> entry : object.values().entrySet()) {
            String pointer = "/materials/" + escape(entry.getKey());
            if (!MATERIAL_SLOT_PATTERN.matcher(entry.getKey()).matches()) {
                throw fail("BLENDLIB-X9-DESC-002", pointer, "Material slot name is outside the X9 canonical grammar", "");
            }
            JsonObject material = object(entry.getValue(), pointer);
            rejectUnknown(material, MATERIAL_FIELDS, pointer);
            BlendResourceId baseColor = textureId(string(required(material, "base_color", pointer + "/base_color"),
                    pointer + "/base_color"), pointer + "/base_color");
            ExperimentalMaterialDefinition.Mode mode;
            try {
                mode = material.containsKey("mode")
                        ? ExperimentalMaterialDefinition.Mode.fromSerializedName(string(material.get("mode"), pointer + "/mode"))
                        : ExperimentalMaterialDefinition.Mode.OPAQUE;
            } catch (IllegalArgumentException exception) {
                throw fail("BLENDLIB-X9-DESC-002", pointer + "/mode", "Unsupported X9 material mode", "");
            }
            boolean doubleSided = material.containsKey("double_sided")
                    ? bool(material.get("double_sided"), pointer + "/double_sided")
                    : false;
            double metallic = material.containsKey("metallic_factor")
                    ? finiteRange(number(material.get("metallic_factor"), pointer + "/metallic_factor"), pointer + "/metallic_factor", 0.0, 1.0)
                    : 0.0;
            double roughness = material.containsKey("roughness_factor")
                    ? finiteRange(number(material.get("roughness_factor"), pointer + "/roughness_factor"), pointer + "/roughness_factor", 0.0, 1.0)
                    : 1.0;
            BlendResourceId normal = optionalTexture(material, "normal_texture", pointer);
            BlendResourceId occlusion = optionalTexture(material, "occlusion_texture", pointer);
            BlendResourceId emissive = optionalTexture(material, "emissive_texture", pointer);
            List<Double> emissiveFactor = material.containsKey("emissive_factor")
                    ? vector3(array(material.get("emissive_factor"), pointer + "/emissive_factor"), pointer + "/emissive_factor")
                    : List.of(0.0, 0.0, 0.0);
            Double alphaCutoff = material.containsKey("alpha_cutoff")
                    ? finiteRange(number(material.get("alpha_cutoff"), pointer + "/alpha_cutoff"), pointer + "/alpha_cutoff", 0.0, 1.0)
                    : null;
            try {
                result.put(entry.getKey(), new ExperimentalMaterialDefinition(baseColor, mode, doubleSided, metallic, roughness,
                        normal, occlusion, emissive, emissiveFactor, alphaCutoff));
            } catch (IllegalArgumentException exception) {
                throw fail("BLENDLIB-X9-DESC-002", pointer, "X9 material metadata is invalid", "");
            }
        }
        return result;
    }

    private BlendResourceId optionalTexture(JsonObject material, String field, String materialPointer) {
        if (!material.containsKey(field)) {
            return null;
        }
        String pointer = materialPointer + "/" + field;
        return textureId(string(material.get(field), pointer), pointer);
    }

    private List<Double> vector3(JsonArray array, String pointer) {
        if (array.size() != 3) {
            throw fail("BLENDLIB-X9-DESC-002", pointer, "Expected exactly three material vector values", "");
        }
        List<Double> result = new ArrayList<>(3);
        for (int index = 0; index < 3; index++) {
            result.add(finiteRange(number(array.get(index), pointer + "/" + index), pointer + "/" + index, 0.0, 1.0));
        }
        return List.copyOf(result);
    }

    private CapabilityLists capabilities(JsonObject object) {
        if (object.size() > limits.maxCapabilities()) {
            throw fail("BLENDLIB-X9-LIMIT-001", "/capabilities", "X9 capability declaration limit exceeded", "");
        }
        List<ExperimentalCapabilityRequirement> requiredCapabilities = new ArrayList<>();
        List<ExperimentalCapabilityRequirement> optionalCapabilities = new ArrayList<>();
        for (Map.Entry<String, JsonValue> entry : object.values().entrySet()) {
            String itemPointer = "/capabilities/" + escape(entry.getKey());
            BlendResourceId id = resourceId(entry.getKey(), itemPointer);
            JsonObject item = object(entry.getValue(), itemPointer);
            rejectUnknown(item, CAPABILITY_FIELDS, itemPointer);
            String requirementKind = string(required(item, "requirement", itemPointer + "/requirement"),
                    itemPointer + "/requirement");
            boolean requiredCapability;
            if ("required".equals(requirementKind)) {
                requiredCapability = true;
            } else if ("optional".equals(requirementKind)) {
                requiredCapability = false;
            } else {
                throw fail("BLENDLIB-X9-DESC-002", itemPointer + "/requirement",
                        "Capability requirement must be required or optional", "");
            }
            ExperimentalSemVer min = version(string(required(item, "min_version", itemPointer + "/min_version"),
                    itemPointer + "/min_version"), itemPointer + "/min_version");
            ExperimentalSemVer max = version(string(required(item, "max_version", itemPointer + "/max_version"),
                    itemPointer + "/max_version"), itemPointer + "/max_version");
            OptionalCapabilityFallback fallback;
            if (requiredCapability) {
                if (item.containsKey("fallback")) {
                    throw fail("BLENDLIB-X9-DESC-002", itemPointer + "/fallback",
                            "Required capabilities must not declare an optional fallback", "");
                }
                fallback = OptionalCapabilityFallback.FAIL_CLOSED;
            } else {
                try {
                    fallback = OptionalCapabilityFallback.fromSerializedName(string(
                            required(item, "fallback", itemPointer + "/fallback"), itemPointer + "/fallback"));
                } catch (IllegalArgumentException exception) {
                    throw fail("BLENDLIB-X9-DESC-002", itemPointer + "/fallback", "Unknown optional capability fallback", "");
                }
                if (fallback == OptionalCapabilityFallback.FAIL_CLOSED) {
                    throw fail("BLENDLIB-X9-DESC-002", itemPointer + "/fallback", "Optional capabilities require a concrete fallback", "");
                }
            }
            try {
                ExperimentalCapabilityRequirement requirement =
                        new ExperimentalCapabilityRequirement(id, min, max, requiredCapability, fallback);
                (requiredCapability ? requiredCapabilities : optionalCapabilities).add(requirement);
            } catch (IllegalArgumentException exception) {
                throw fail("BLENDLIB-X9-DESC-002", itemPointer, "Capability version range is invalid", "");
            }
        }
        return new CapabilityLists(List.copyOf(requiredCapabilities), List.copyOf(optionalCapabilities));
    }

    private static JsonValue required(JsonObject object, String key, String pointer) {
        JsonValue value = object.get(key);
        if (value == null) {
            throw fail("BLENDLIB-X9-DESC-002", pointer, "Required X9 descriptor field is missing", "");
        }
        return value;
    }

    private static void rejectUnknown(JsonObject object, Set<String> allowed, String pointer) {
        for (String key : object.values().keySet()) {
            if (!allowed.contains(key)) {
                throw fail("BLENDLIB-X9-DESC-002", pointer + "/" + escape(key), "Unknown X9 descriptor field", "");
            }
        }
    }

    private static JsonObject object(JsonValue value, String pointer) {
        if (value instanceof JsonObject object) {
            return object;
        }
        throw fail("BLENDLIB-X9-DESC-002", pointer, "Expected a JSON object", "");
    }

    private static JsonArray array(JsonValue value, String pointer) {
        if (value instanceof JsonArray array) {
            return array;
        }
        throw fail("BLENDLIB-X9-DESC-002", pointer, "Expected a JSON array", "");
    }

    private static String string(JsonValue value, String pointer) {
        if (value instanceof JsonString string) {
            return string.value();
        }
        throw fail("BLENDLIB-X9-DESC-002", pointer, "Expected a JSON string", "");
    }

    private static boolean bool(JsonValue value, String pointer) {
        if (value instanceof JsonBoolean bool) {
            return bool.value();
        }
        throw fail("BLENDLIB-X9-DESC-002", pointer, "Expected a JSON boolean", "");
    }

    private static JsonNumber number(JsonValue value, String pointer) {
        if (value instanceof JsonNumber number) {
            return number;
        }
        throw fail("BLENDLIB-X9-DESC-002", pointer, "Expected a JSON number", "");
    }

    private static int integer(JsonValue value, String pointer) {
        try {
            return number(value, pointer).asIntExact();
        } catch (IllegalArgumentException exception) {
            throw fail("BLENDLIB-X9-DESC-002", pointer, "Expected a 32-bit integer", "");
        }
    }

    private static double finitePositive(JsonNumber value, String pointer) {
        try {
            double result = value.asDouble();
            if (!Double.isFinite(result) || result <= 0.0) {
                throw new IllegalArgumentException("not finite positive");
            }
            return result;
        } catch (IllegalArgumentException exception) {
            throw fail("BLENDLIB-X9-DESC-002", pointer, "Expected a finite positive number", "");
        }
    }

    private static double finiteRange(JsonNumber value, String pointer, double minimum, double maximum) {
        try {
            double result = value.asDouble();
            if (!Double.isFinite(result) || result < minimum || result > maximum) {
                throw new IllegalArgumentException("outside range");
            }
            return result;
        } catch (IllegalArgumentException exception) {
            throw fail("BLENDLIB-X9-DESC-002", pointer, "Expected a finite number in the declared range", "");
        }
    }

    private static ExperimentalSemVer version(String value, String pointer) {
        try {
            return ExperimentalSemVer.parse(value);
        } catch (IllegalArgumentException exception) {
            throw fail("BLENDLIB-X9-DESC-002", pointer, "Invalid bounded semantic-version value", "");
        }
    }

    private static BlendResourceId resourceId(String value, String pointer) {
        try {
            return BlendResourceId.parse(value);
        } catch (IllegalArgumentException exception) {
            throw fail("BLENDLIB-X9-DESC-002", pointer, "Invalid or unsafe resource reference", "");
        }
    }

    private static BlendResourceId textureId(String value, String pointer) {
        BlendResourceId resourceId = resourceId(value, pointer);
        String path = resourceId.path();
        String prefix = "textures/";
        if (!path.startsWith(prefix) || !path.endsWith(".png")
                || path.substring(prefix.length()).length() <= ".png".length()) {
            throw fail("BLENDLIB-X9-DESC-002", pointer, "Texture resources must stay under textures/ and end in .png", "");
        }
        return resourceId;
    }

    private static boolean isModels3dGlb(BlendResourceId resourceId) {
        String path = resourceId.path();
        String prefix = "models3d/";
        return path.startsWith(prefix) && path.endsWith(".glb")
                && path.substring(prefix.length()).length() > ".glb".length();
    }

    private static String escape(String key) {
        return key.replace("~", "~0").replace("/", "~1");
    }

    private static ExperimentalProfileValidationException fail(String code, String location, String message, String fallback) {
        return new ExperimentalProfileValidationException(new ExperimentalProfileDiagnostic(
                ExperimentalProfileDiagnostic.Severity.ERROR, code, location, message, fallback));
    }

    private record CapabilityLists(
            List<ExperimentalCapabilityRequirement> required, List<ExperimentalCapabilityRequirement> optional) {
    }
}
