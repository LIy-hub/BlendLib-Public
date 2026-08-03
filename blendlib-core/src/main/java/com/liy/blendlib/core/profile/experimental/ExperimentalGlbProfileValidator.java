package com.liy.blendlib.core.profile.experimental;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.glb.AccessorInfo;
import com.liy.blendlib.core.glb.GlbAccessorReader;
import com.liy.blendlib.core.json.JsonArray;
import com.liy.blendlib.core.json.JsonNumber;
import com.liy.blendlib.core.json.JsonObject;
import com.liy.blendlib.core.json.JsonString;
import com.liy.blendlib.core.json.JsonValue;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Strict feature-envelope validator for X9's validation-only profiles.
 *
 * <p>Container, accessor bounds, scene hierarchy, and skin structure arrive
 * from {@link ExperimentalGlbStructureValidator}. This class validates only
 * profile-specific material, mesh, morph, and animation semantics and produces
 * counts rather than a runtime model.</p>
 */
final class ExperimentalGlbProfileValidator {
    private static final int FLOAT = 5126;
    private static final int UNSIGNED_BYTE = 5121;
    private static final int UNSIGNED_SHORT = 5123;
    private static final Set<String> MATERIAL_FIELDS = Set.of("name", "extensions");
    private static final Set<String> MESH_FIELDS = Set.of("name", "primitives", "weights", "extensions");
    private static final Set<String> PRIMITIVE_FIELDS =
            Set.of("attributes", "indices", "material", "mode", "targets", "extensions");
    private static final Set<String> PRIMITIVE_ATTRIBUTES = Set.of(
            "POSITION", "NORMAL", "TEXCOORD_0", "TEXCOORD_1", "COLOR_0", "JOINTS_0", "WEIGHTS_0");
    private static final Set<String> MORPH_TARGET_ATTRIBUTES = Set.of("POSITION", "NORMAL", "TANGENT");
    private static final Set<String> ANIMATION_FIELDS = Set.of("name", "samplers", "channels", "extensions");
    private static final Set<String> SAMPLER_FIELDS = Set.of("input", "output", "interpolation", "extensions");
    private static final Set<String> CHANNEL_FIELDS = Set.of("sampler", "target", "extensions");
    private static final Set<String> TARGET_FIELDS = Set.of("node", "path", "extensions");

    private final ExperimentalProfileLimits limits;

    ExperimentalGlbProfileValidator(ExperimentalProfileLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    Result validate(
            ExperimentalDescriptor descriptor,
            JsonObject root,
            ExperimentalGlbStructureValidator.Result structure,
            Set<BlendResourceId> negotiatedCapabilities) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(structure, "structure");
        Objects.requireNonNull(negotiatedCapabilities, "negotiatedCapabilities");

        int materialCount = validateMaterials(root, descriptor, negotiatedCapabilities);
        MeshResult meshResult = validateMeshes(
                root, descriptor.profile(), structure.accessors(), materialCount, negotiatedCapabilities);
        validateBindings(descriptor.profile(), structure, meshResult.meshes());
        int cubicSplineSamplers = validateAnimations(
                root, descriptor.profile(), structure, meshResult.meshes(), negotiatedCapabilities);

        requireFeatureCount(negotiatedCapabilities, "blendlib:vertex-color",
                meshResult.vertexColorPrimitives(), meshResult.primitives(), "/meshes");
        requireFeatureCount(negotiatedCapabilities, "blendlib:multiple-uv",
                meshResult.secondaryUvPrimitives(), meshResult.primitives(), "/meshes");
        if (negotiatedCapabilities.contains(BlendResourceId.parse("blendlib:morph-targets"))
                && meshResult.morphTargets() == 0) {
            throw error("BLENDLIB-X9-GLB-015", "/meshes",
                    "Declared morph-target capability requires validated morph content");
        }
        if (negotiatedCapabilities.contains(BlendResourceId.parse("blendlib:cubic-spline"))
                && cubicSplineSamplers == 0) {
            throw error("BLENDLIB-X9-GLB-015", "/animations",
                    "Declared cubic-spline capability requires a validated CUBICSPLINE sampler");
        }
        return new Result(
                meshResult.primitives(),
                meshResult.morphTargets(),
                cubicSplineSamplers,
                meshResult.vertexColorPrimitives(),
                meshResult.secondaryUvPrimitives());
    }

    private int validateMaterials(
            JsonObject root,
            ExperimentalDescriptor descriptor,
            Set<BlendResourceId> capabilities) {
        JsonArray materials = array(required(root, "materials", "/materials"), "/materials");
        if (materials.size() == 0 || materials.size() > limits.maxMaterials()) {
            throw error(materials.size() == 0 ? "BLENDLIB-X9-GLB-015" : "BLENDLIB-X9-LIMIT-001",
                    "/materials", "X9 GLB material count is outside bounds");
        }
        Set<String> names = new HashSet<>();
        for (int index = 0; index < materials.size(); index++) {
            String pointer = "/materials/" + index;
            JsonObject material = object(materials.get(index), pointer);
            rejectUnknown(material, MATERIAL_FIELDS, pointer);
            String name = string(required(material, "name", pointer + "/name"), pointer + "/name");
            if (name.isBlank() || !names.add(name) || !descriptor.materials().containsKey(name)) {
                throw error("BLENDLIB-X9-GLB-015", pointer + "/name",
                        "GLB material names must be unique and mapped by the X9 descriptor");
            }
        }
        if (!names.equals(descriptor.materials().keySet())) {
            throw error("BLENDLIB-X9-GLB-015", "/materials",
                    "Descriptor and GLB material slot sets must match exactly");
        }
        if (capabilities.contains(BlendResourceId.parse("blendlib:richer-material-metadata"))
                && descriptor.materials().values().stream().noneMatch(ExperimentalGlbProfileValidator::hasRichMaterialMetadata)) {
            throw error("BLENDLIB-X9-DESC-003", "/materials",
                    "Declared richer-material capability requires non-default bounded material metadata");
        }
        return materials.size();
    }

    private static boolean hasRichMaterialMetadata(ExperimentalMaterialDefinition material) {
        return material.mode() != ExperimentalMaterialDefinition.Mode.OPAQUE
                || material.doubleSided()
                || material.metallicFactor() != 0.0
                || material.roughnessFactor() != 1.0
                || material.normalTexture() != null
                || material.occlusionTexture() != null
                || material.emissiveTexture() != null
                || material.emissiveFactor().stream().anyMatch(value -> value != 0.0)
                || material.alphaCutoff() != null;
    }

    private MeshResult validateMeshes(
            JsonObject root,
            ExperimentalProfile profile,
            GlbAccessorReader accessors,
            int materialCount,
            Set<BlendResourceId> capabilities) {
        JsonArray meshes = array(required(root, "meshes", "/meshes"), "/meshes");
        long totalVertices = 0;
        long totalIndices = 0;
        int primitiveCount = 0;
        int morphTargetCount = 0;
        int colorPrimitiveCount = 0;
        int uv1PrimitiveCount = 0;
        List<MeshInfo> meshInfos = new ArrayList<>(meshes.size());

        for (int meshIndex = 0; meshIndex < meshes.size(); meshIndex++) {
            String meshPointer = "/meshes/" + meshIndex;
            JsonObject mesh = object(meshes.get(meshIndex), meshPointer);
            rejectUnknown(mesh, MESH_FIELDS, meshPointer);
            validateOptionalString(mesh, "name", meshPointer + "/name");
            JsonArray primitives = array(required(mesh, "primitives", meshPointer + "/primitives"),
                    meshPointer + "/primitives");
            if (primitives.size() == 0) {
                throw error("BLENDLIB-X9-GLB-015", meshPointer + "/primitives",
                        "Every X9 mesh requires at least one primitive");
            }
            primitiveCount = boundedSum(primitiveCount, primitives.size(), limits.baseGlbLimits().maxNodes(),
                    meshPointer + "/primitives", "Primitive count exceeds the X9 structural bound");

            Integer meshTargetCount = null;
            int meshMaxJointIndex = -1;
            for (int primitiveIndex = 0; primitiveIndex < primitives.size(); primitiveIndex++) {
                String primitivePointer = meshPointer + "/primitives/" + primitiveIndex;
                JsonObject primitive = object(primitives.get(primitiveIndex), primitivePointer);
                rejectUnknown(primitive, PRIMITIVE_FIELDS, primitivePointer);
                int mode = primitive.containsKey("mode")
                        ? integer(primitive.get("mode"), primitivePointer + "/mode") : 4;
                if (mode != 4) {
                    throw error("BLENDLIB-X9-GLB-015", primitivePointer + "/mode",
                            "Only TRIANGLES primitives are accepted");
                }
                int material = integer(required(primitive, "material", primitivePointer + "/material"),
                        primitivePointer + "/material");
                if (material < 0 || material >= materialCount) {
                    throw error("BLENDLIB-X9-GLB-015", primitivePointer + "/material",
                            "Primitive material index is outside the validated material list");
                }

                JsonObject attributes = object(required(primitive, "attributes", primitivePointer + "/attributes"),
                        primitivePointer + "/attributes");
                validateAttributeNames(attributes, primitivePointer + "/attributes");
                int positionAccessor = accessorIndex(attributes, "POSITION", primitivePointer + "/attributes/POSITION");
                int normalAccessor = accessorIndex(attributes, "NORMAL", primitivePointer + "/attributes/NORMAL");
                int uv0Accessor = accessorIndex(attributes, "TEXCOORD_0", primitivePointer + "/attributes/TEXCOORD_0");
                int jointAccessor = accessorIndex(attributes, "JOINTS_0", primitivePointer + "/attributes/JOINTS_0");
                int weightAccessor = accessorIndex(attributes, "WEIGHTS_0", primitivePointer + "/attributes/WEIGHTS_0");
                int indexAccessor = integer(required(primitive, "indices", primitivePointer + "/indices"),
                        primitivePointer + "/indices");

                AccessorInfo position = requireFloat(accessors, positionAccessor, "VEC3",
                        primitivePointer + "/attributes/POSITION");
                AccessorInfo normal = requireFloat(accessors, normalAccessor, "VEC3",
                        primitivePointer + "/attributes/NORMAL");
                AccessorInfo uv0 = requireUv(accessors, uv0Accessor, primitivePointer + "/attributes/TEXCOORD_0");
                if (position.count() == 0 || normal.count() != position.count() || uv0.count() != position.count()) {
                    throw error("BLENDLIB-X9-GLB-015", primitivePointer,
                            "Position, normal, and UV0 counts must match and be non-zero");
                }
                AccessorInfo indexInfo = accessors.info(indexAccessor);
                if (indexInfo.normalized()) {
                    throw error("BLENDLIB-X9-GLB-015", primitivePointer + "/indices",
                            "Index accessors must not be normalized");
                }
                int[] indices = accessors.readIndexElements(indexAccessor);
                if (indices.length == 0 || indices.length % 3 != 0) {
                    throw error("BLENDLIB-X9-GLB-015", primitivePointer + "/indices",
                            "Triangle index count is invalid");
                }
                for (int value : indices) {
                    if (value < 0 || value >= position.count()) {
                        throw error("BLENDLIB-X9-GLB-015", primitivePointer + "/indices",
                                "Triangle index references a missing vertex");
                    }
                }
                totalVertices = addBounded(totalVertices, position.count(), limits.baseGlbLimits().maxVertices(),
                        primitivePointer + "/attributes/POSITION", "Vertex limit exceeded");
                totalIndices = addBounded(totalIndices, indices.length, limits.baseGlbLimits().maxIndices(),
                        primitivePointer + "/indices", "Index limit exceeded");
                float[] positionValues = accessors.readFloatElements(positionAccessor, "VEC3");
                validateExactFloatBounds(root, positionAccessor, positionValues, position.componentCount(),
                        primitivePointer + "/attributes/POSITION");
                accessors.readFloatElements(normalAccessor, "VEC3");
                readUv(accessors, uv0Accessor, uv0);

                int maxJoint = validateSkinAttributes(
                        accessors, jointAccessor, weightAccessor, position.count(), primitivePointer);
                meshMaxJointIndex = Math.max(meshMaxJointIndex, maxJoint);

                if (attributes.containsKey("COLOR_0")) {
                    requireCapability(capabilities, "blendlib:vertex-color",
                            primitivePointer + "/attributes/COLOR_0");
                    AccessorInfo color = requireColor(accessors,
                            accessorIndex(attributes, "COLOR_0", primitivePointer + "/attributes/COLOR_0"),
                            primitivePointer + "/attributes/COLOR_0");
                    if (color.count() != position.count()) {
                        throw error("BLENDLIB-X9-GLB-015", primitivePointer + "/attributes/COLOR_0",
                                "Vertex color count must match POSITION");
                    }
                    readColor(accessors, color);
                    colorPrimitiveCount++;
                }
                if (attributes.containsKey("TEXCOORD_1")) {
                    requireCapability(capabilities, "blendlib:multiple-uv",
                            primitivePointer + "/attributes/TEXCOORD_1");
                    AccessorInfo uv1 = requireUv(accessors,
                            accessorIndex(attributes, "TEXCOORD_1", primitivePointer + "/attributes/TEXCOORD_1"),
                            primitivePointer + "/attributes/TEXCOORD_1");
                    if (uv1.count() != position.count()) {
                        throw error("BLENDLIB-X9-GLB-015", primitivePointer + "/attributes/TEXCOORD_1",
                                "Secondary UV count must match POSITION");
                    }
                    readUv(accessors, uv1.index(), uv1);
                    uv1PrimitiveCount++;
                }

                int targets = validateMorphTargets(
                        primitive, profile, accessors, position.count(), primitivePointer, capabilities);
                if (meshTargetCount == null) {
                    meshTargetCount = targets;
                } else if (meshTargetCount != targets) {
                    throw error("BLENDLIB-X9-GLB-015", meshPointer + "/primitives",
                            "All primitives in one mesh must declare the same morph-target count");
                }
                int totalMorphLimit = (int) Math.min(Integer.MAX_VALUE,
                        (long) limits.maxMorphTargetsPerPrimitive() * limits.baseGlbLimits().maxNodes());
                morphTargetCount = boundedSum(morphTargetCount, targets, totalMorphLimit,
                        primitivePointer + "/targets", "Total morph-target count exceeds the X9 bound");
            }

            int targetCount = meshTargetCount == null ? 0 : meshTargetCount;
            validateMeshWeights(mesh, profile, targetCount, meshPointer);
            meshInfos.add(new MeshInfo(targetCount, meshMaxJointIndex));
        }
        return new MeshResult(
                primitiveCount,
                morphTargetCount,
                colorPrimitiveCount,
                uv1PrimitiveCount,
                List.copyOf(meshInfos));
    }

    private int validateSkinAttributes(
            GlbAccessorReader accessors,
            int jointAccessor,
            int weightAccessor,
            int vertexCount,
            String primitivePointer) {
        AccessorInfo joints = accessors.info(jointAccessor);
        AccessorInfo weights = accessors.info(weightAccessor);
        if (!"VEC4".equals(joints.type())
                || (joints.componentType() != UNSIGNED_BYTE && joints.componentType() != UNSIGNED_SHORT)
                || joints.normalized()
                || joints.count() != vertexCount
                || !"VEC4".equals(weights.type())
                || weights.count() != vertexCount) {
            throw error("BLENDLIB-X9-SKIN-001", primitivePointer + "/attributes",
                    "X9 skin attributes have invalid type, normalization, or count");
        }
        int[] jointValues = accessors.readUnsignedElements(jointAccessor, "VEC4");
        float[] weightValues = accessors.readWeightElements(weightAccessor);
        int maxJoint = -1;
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            double sum = 0.0;
            for (int component = 0; component < 4; component++) {
                int offset = vertex * 4 + component;
                maxJoint = Math.max(maxJoint, jointValues[offset]);
                float value = weightValues[offset];
                if (!Float.isFinite(value) || value < 0.0f || value > 1.0f) {
                    throw error("BLENDLIB-X9-SKIN-001", primitivePointer + "/attributes/WEIGHTS_0",
                            "Skin weights must be finite values in [0, 1]");
                }
                if (value > 0.0f) {
                    for (int previous = 0; previous < component; previous++) {
                        int previousOffset = vertex * 4 + previous;
                        if (weightValues[previousOffset] > 0.0f
                                && jointValues[previousOffset] == jointValues[offset]) {
                            throw error("BLENDLIB-X9-SKIN-001", primitivePointer + "/attributes/JOINTS_0",
                                    "A vertex must not repeat a joint across non-zero influences");
                        }
                    }
                }
                sum += value;
            }
            if (Math.abs(sum - 1.0) > 1.0e-4) {
                throw error("BLENDLIB-X9-SKIN-001", primitivePointer + "/attributes/WEIGHTS_0",
                        "Each vertex skin-weight vector must sum to one");
            }
        }
        return maxJoint;
    }

    private int validateMorphTargets(
            JsonObject primitive,
            ExperimentalProfile profile,
            GlbAccessorReader accessors,
            int vertexCount,
            String primitivePointer,
            Set<BlendResourceId> capabilities) {
        JsonValue targetValue = primitive.get("targets");
        if (targetValue == null) {
            if (profile == ExperimentalProfile.MORPH_V1) {
                throw error("BLENDLIB-X9-GLB-015", primitivePointer + "/targets",
                        "morph_v1 requires morph targets on every primitive");
            }
            return 0;
        }
        if (profile != ExperimentalProfile.MORPH_V1) {
            throw error("BLENDLIB-X9-GLB-015", primitivePointer + "/targets",
                    "skinned_v2 does not accept morph targets");
        }
        requireCapability(capabilities, "blendlib:morph-targets", primitivePointer + "/targets");
        JsonArray targets = array(targetValue, primitivePointer + "/targets");
        if (targets.size() == 0 || targets.size() > limits.maxMorphTargetsPerPrimitive()) {
            throw error(targets.size() == 0 ? "BLENDLIB-X9-GLB-015" : "BLENDLIB-X9-LIMIT-001",
                    primitivePointer + "/targets", "Morph target count is outside X9 bounds");
        }
        for (int targetIndex = 0; targetIndex < targets.size(); targetIndex++) {
            String targetPointer = primitivePointer + "/targets/" + targetIndex;
            JsonObject target = object(targets.get(targetIndex), targetPointer);
            if (!target.containsKey("POSITION")) {
                throw error("BLENDLIB-X9-GLB-015", targetPointer,
                        "Each X9 morph target requires POSITION deltas");
            }
            for (String attribute : target.values().keySet()) {
                if (!MORPH_TARGET_ATTRIBUTES.contains(attribute)) {
                    throw error("BLENDLIB-X9-GLB-015", targetPointer + "/" + attribute,
                            "Unsupported X9 morph target attribute");
                }
                int accessor = integer(target.get(attribute), targetPointer + "/" + attribute);
                AccessorInfo info = requireFloat(accessors, accessor, "VEC3", targetPointer + "/" + attribute);
                if (info.count() != vertexCount) {
                    throw error("BLENDLIB-X9-GLB-015", targetPointer + "/" + attribute,
                            "Morph target count must match POSITION");
                }
                accessors.readFloatElements(accessor, "VEC3");
            }
        }
        return targets.size();
    }

    private void validateMeshWeights(
            JsonObject mesh, ExperimentalProfile profile, int targetCount, String meshPointer) {
        JsonValue weightsValue = mesh.get("weights");
        if (profile == ExperimentalProfile.SKINNED_V2) {
            if (weightsValue != null || targetCount != 0) {
                throw error("BLENDLIB-X9-GLB-015", meshPointer + "/weights",
                        "skinned_v2 does not accept morph weights or targets");
            }
            return;
        }
        if (weightsValue == null) {
            throw error("BLENDLIB-X9-GLB-015", meshPointer + "/weights",
                    "morph_v1 requires explicit mesh weights");
        }
        JsonArray weights = array(weightsValue, meshPointer + "/weights");
        if (targetCount == 0 || weights.size() != targetCount) {
            throw error("BLENDLIB-X9-GLB-015", meshPointer + "/weights",
                    "Mesh weights must match the common per-primitive morph-target count");
        }
        for (int index = 0; index < weights.size(); index++) {
            finite(number(weights.get(index), meshPointer + "/weights/" + index),
                    meshPointer + "/weights/" + index);
        }
    }

    private void validateBindings(
            ExperimentalProfile profile,
            ExperimentalGlbStructureValidator.Result structure,
            List<MeshInfo> meshes) {
        for (ExperimentalGlbStructureValidator.NodeInfo node : structure.nodes()) {
            if (node.meshIndex() < 0) {
                continue;
            }
            MeshInfo mesh = meshes.get(node.meshIndex());
            ExperimentalGlbStructureValidator.SkinInfo skin = structure.skins().get(node.skinIndex());
            if (mesh.maxJointIndex() >= skin.joints().size()) {
                throw error("BLENDLIB-X9-SKIN-001", "/nodes/" + node.index() + "/skin",
                        "Vertex JOINTS_0 index is outside the bound skin joint list");
            }
            if (profile == ExperimentalProfile.MORPH_V1) {
                if (!node.weights().isEmpty() && node.weights().size() != mesh.targetCount()) {
                    throw error("BLENDLIB-X9-GLB-015", "/nodes/" + node.index() + "/weights",
                            "Node morph weights must match the bound mesh target count");
                }
            } else if (!node.weights().isEmpty()) {
                throw error("BLENDLIB-X9-GLB-015", "/nodes/" + node.index() + "/weights",
                        "skinned_v2 node must not declare morph weights");
            }
        }
    }

    private int validateAnimations(
            JsonObject root,
            ExperimentalProfile profile,
            ExperimentalGlbStructureValidator.Result structure,
            List<MeshInfo> meshes,
            Set<BlendResourceId> capabilities) {
        JsonValue value = root.get("animations");
        if (value == null) {
            return 0;
        }
        JsonArray animations = array(value, "/animations");
        if (animations.size() == 0 || animations.size() > limits.maxAnimations()) {
            throw error(animations.size() > limits.maxAnimations()
                            ? "BLENDLIB-X9-LIMIT-001" : "BLENDLIB-X9-GLB-015",
                    "/animations", "Animation clip count is outside X9 bounds");
        }
        int cubicSplineSamplers = 0;
        long totalInputSamples = 0;
        long totalOutputValues = 0;
        for (int animationIndex = 0; animationIndex < animations.size(); animationIndex++) {
            String animationPointer = "/animations/" + animationIndex;
            JsonObject animation = object(animations.get(animationIndex), animationPointer);
            rejectUnknown(animation, ANIMATION_FIELDS, animationPointer);
            validateOptionalString(animation, "name", animationPointer + "/name");
            JsonArray samplerArray = array(required(animation, "samplers", animationPointer + "/samplers"),
                    animationPointer + "/samplers");
            JsonArray channelArray = array(required(animation, "channels", animationPointer + "/channels"),
                    animationPointer + "/channels");
            if (samplerArray.size() == 0 || samplerArray.size() > limits.maxAnimationSamplers()
                    || channelArray.size() == 0 || channelArray.size() > limits.maxAnimationSamplers()) {
                throw error(samplerArray.size() > limits.maxAnimationSamplers()
                                || channelArray.size() > limits.maxAnimationSamplers()
                                ? "BLENDLIB-X9-LIMIT-001" : "BLENDLIB-X9-GLB-015",
                        animationPointer, "Animation sampler/channel count is outside X9 bounds");
            }
            List<SamplerInfo> samplers = new ArrayList<>(samplerArray.size());
            for (int samplerIndex = 0; samplerIndex < samplerArray.size(); samplerIndex++) {
                String pointer = animationPointer + "/samplers/" + samplerIndex;
                JsonObject sampler = object(samplerArray.get(samplerIndex), pointer);
                rejectUnknown(sampler, SAMPLER_FIELDS, pointer);
                int input = integer(required(sampler, "input", pointer + "/input"), pointer + "/input");
                int output = integer(required(sampler, "output", pointer + "/output"), pointer + "/output");
                String interpolation = sampler.containsKey("interpolation")
                        ? string(sampler.get("interpolation"), pointer + "/interpolation") : "LINEAR";
                if (!Set.of("LINEAR", "STEP", "CUBICSPLINE").contains(interpolation)) {
                    throw error("BLENDLIB-X9-GLB-015", pointer + "/interpolation",
                            "Unsupported animation interpolation");
                }
                AccessorInfo inputInfo = requireFloat(structure.accessors(), input, "SCALAR", pointer + "/input");
                if (inputInfo.count() == 0) {
                    throw error("BLENDLIB-X9-GLB-015", pointer + "/input",
                            "Animation input must not be empty");
                }
                totalInputSamples = addBounded(totalInputSamples, inputInfo.count(),
                        limits.baseGlbLimits().maxKeyframeSamples(), pointer + "/input",
                        "Animation input sample limit exceeded");
                float[] times = structure.accessors().readFloatElements(input, "SCALAR");
                validateExactFloatBounds(root, input, times, 1, pointer + "/input");
                validateTimes(times, pointer + "/input");
                samplers.add(new SamplerInfo(output, interpolation, times.length));
            }

            int[] samplerReferences = new int[samplers.size()];
            Set<String> targets = new HashSet<>();
            for (int channelIndex = 0; channelIndex < channelArray.size(); channelIndex++) {
                String channelPointer = animationPointer + "/channels/" + channelIndex;
                JsonObject channel = object(channelArray.get(channelIndex), channelPointer);
                rejectUnknown(channel, CHANNEL_FIELDS, channelPointer);
                int samplerIndex = integer(required(channel, "sampler", channelPointer + "/sampler"),
                        channelPointer + "/sampler");
                if (samplerIndex < 0 || samplerIndex >= samplers.size()) {
                    throw error("BLENDLIB-X9-GLB-015", channelPointer + "/sampler",
                            "Animation sampler reference is invalid");
                }
                samplerReferences[samplerIndex]++;
                JsonObject target = object(required(channel, "target", channelPointer + "/target"),
                        channelPointer + "/target");
                rejectUnknown(target, TARGET_FIELDS, channelPointer + "/target");
                int targetNode = integer(required(target, "node", channelPointer + "/target/node"),
                        channelPointer + "/target/node");
                if (targetNode < 0 || targetNode >= structure.nodes().size()
                        || !structure.activeNodes().contains(targetNode)) {
                    throw error("BLENDLIB-X9-GLB-015", channelPointer + "/target/node",
                            "Animation target must reference an active node");
                }
                String path = string(required(target, "path", channelPointer + "/target/path"),
                        channelPointer + "/target/path");
                if (!Set.of("translation", "rotation", "scale", "weights").contains(path)) {
                    throw error("BLENDLIB-X9-GLB-015", channelPointer + "/target/path",
                            "Unsupported animation target path");
                }
                if (structure.nodes().get(targetNode).matrixDeclared()) {
                    throw error("BLENDLIB-X9-GLB-015", channelPointer + "/target/node",
                            "Animated node must not declare matrix");
                }
                if (!targets.add(targetNode + ":" + path)) {
                    throw error("BLENDLIB-X9-GLB-015", channelPointer + "/target",
                            "Animation channels must not duplicate a node/path target");
                }

                SamplerInfo sampler = samplers.get(samplerIndex);
                int morphComponents = 1;
                String expectedType = switch (path) {
                    case "rotation" -> "VEC4";
                    case "weights" -> {
                        if (profile != ExperimentalProfile.MORPH_V1) {
                            throw error("BLENDLIB-X9-GLB-015", channelPointer + "/target/path",
                                    "weights animation requires morph_v1");
                        }
                        int mesh = structure.nodes().get(targetNode).meshIndex();
                        if (mesh < 0 || meshes.get(mesh).targetCount() == 0) {
                            throw error("BLENDLIB-X9-GLB-015", channelPointer + "/target/node",
                                    "weights animation target must bind a morph mesh");
                        }
                        morphComponents = meshes.get(mesh).targetCount();
                        yield "SCALAR";
                    }
                    default -> "VEC3";
                };
                AccessorInfo output = requireFloat(
                        structure.accessors(), sampler.outputAccessor(), expectedType, channelPointer);
                long expectedCount;
                try {
                    expectedCount = Math.multiplyExact(
                            Math.multiplyExact((long) sampler.inputCount(), morphComponents),
                            "CUBICSPLINE".equals(sampler.interpolation()) ? 3L : 1L);
                } catch (ArithmeticException exception) {
                    throw error("BLENDLIB-X9-LIMIT-001", channelPointer,
                            "Animation output cardinality exceeds the X9 bound");
                }
                if (output.count() != expectedCount) {
                    throw error("BLENDLIB-X9-GLB-015", channelPointer,
                            "Animation output cardinality does not match path, morph count, and interpolation");
                }
                totalOutputValues = addBounded(totalOutputValues,
                        (long) output.count() * output.componentCount(),
                        (long) limits.baseGlbLimits().maxKeyframeSamples() * 16L,
                        channelPointer, "Animation output value limit exceeded");
                float[] outputValues = structure.accessors().readFloatElements(sampler.outputAccessor(), expectedType);
                if ("rotation".equals(path)) {
                    validateAnimatedRotations(outputValues, sampler.interpolation(), channelPointer);
                }
                if ("CUBICSPLINE".equals(sampler.interpolation())) {
                    requireCapability(capabilities, "blendlib:cubic-spline", channelPointer);
                    cubicSplineSamplers++;
                }
            }
            for (int index = 0; index < samplerReferences.length; index++) {
                if (samplerReferences[index] != 1) {
                    throw error("BLENDLIB-X9-GLB-015", animationPointer + "/samplers/" + index,
                            "Every X9 animation sampler must be referenced by exactly one channel");
                }
            }
        }
        return cubicSplineSamplers;
    }

    private void validateTimes(float[] times, String pointer) {
        if (times[0] < 0.0f || times[times.length - 1] > limits.maxClipDurationSeconds()) {
            throw error("BLENDLIB-X9-GLB-015", pointer, "Animation time is outside X9 bounds");
        }
        for (int index = 1; index < times.length; index++) {
            if (!(times[index] > times[index - 1])) {
                throw error("BLENDLIB-X9-GLB-015", pointer, "Animation times must be strictly increasing");
            }
        }
    }

    private static void requireFeatureCount(
            Set<BlendResourceId> capabilities,
            String capability,
            int actual,
            int expected,
            String pointer) {
        if (capabilities.contains(BlendResourceId.parse(capability)) && actual != expected) {
            throw error("BLENDLIB-X9-GLB-015", pointer,
                    "Declared capability " + capability + " must be present on every validated primitive");
        }
    }

    private static void validateExactFloatBounds(
            JsonObject root, int accessorIndex, float[] values, int componentCount, String pointer) {
        JsonArray accessorArray = array(required(root, "accessors", "/accessors"), "/accessors");
        JsonObject accessor = object(accessorArray.get(accessorIndex), "/accessors/" + accessorIndex);
        JsonArray minimum = array(required(accessor, "min", "/accessors/" + accessorIndex + "/min"),
                "/accessors/" + accessorIndex + "/min");
        JsonArray maximum = array(required(accessor, "max", "/accessors/" + accessorIndex + "/max"),
                "/accessors/" + accessorIndex + "/max");
        if (minimum.size() != componentCount || maximum.size() != componentCount || values.length == 0) {
            throw error("BLENDLIB-X9-GLB-015", pointer,
                    "Required accessor bounds have invalid cardinality");
        }
        for (int component = 0; component < componentCount; component++) {
            float actualMinimum = Float.POSITIVE_INFINITY;
            float actualMaximum = Float.NEGATIVE_INFINITY;
            for (int index = component; index < values.length; index += componentCount) {
                actualMinimum = Math.min(actualMinimum, values[index]);
                actualMaximum = Math.max(actualMaximum, values[index]);
            }
            String minimumPointer = "/accessors/" + accessorIndex + "/min/" + component;
            String maximumPointer = "/accessors/" + accessorIndex + "/max/" + component;
            double declaredMinimum = finite(number(minimum.get(component), minimumPointer), minimumPointer);
            double declaredMaximum = finite(number(maximum.get(component), maximumPointer), maximumPointer);
            float declaredMinimumFloat = (float) declaredMinimum;
            float declaredMaximumFloat = (float) declaredMaximum;
            if (!Float.isFinite(declaredMinimumFloat) || !Float.isFinite(declaredMaximumFloat)
                    || actualMinimum != declaredMinimumFloat || actualMaximum != declaredMaximumFloat) {
                throw error("BLENDLIB-X9-GLB-015", pointer,
                        "Accessor min/max metadata must exactly match the finite accessor data");
            }
        }
    }

    private static void validateAnimatedRotations(float[] values, String interpolation, String pointer) {
        int elementCount = values.length / 4;
        int firstElement = "CUBICSPLINE".equals(interpolation) ? 1 : 0;
        int elementStep = "CUBICSPLINE".equals(interpolation) ? 3 : 1;
        for (int element = firstElement; element < elementCount; element += elementStep) {
            int offset = element * 4;
            double lengthSquared = 0.0;
            for (int component = 0; component < 4; component++) {
                double value = values[offset + component];
                lengthSquared += value * value;
            }
            if (!Double.isFinite(lengthSquared) || Math.abs(lengthSquared - 1.0) > 1.0e-4) {
                throw error("BLENDLIB-X9-GLB-015", pointer,
                        "Animation rotation values must be normalized quaternions");
            }
        }
    }

    private static void validateOptionalString(JsonObject object, String field, String pointer) {
        if (object.containsKey(field)) {
            string(object.get(field), pointer);
        }
    }

    private static void validateAttributeNames(JsonObject attributes, String pointer) {
        for (String name : attributes.values().keySet()) {
            if (!PRIMITIVE_ATTRIBUTES.contains(name)) {
                throw error("BLENDLIB-X9-GLB-015", pointer + "/" + name,
                        "Unsupported X9 primitive attribute");
            }
        }
    }

    private static AccessorInfo requireFloat(
            GlbAccessorReader accessors, int accessor, String type, String pointer) {
        AccessorInfo info = accessors.info(accessor);
        if (!type.equals(info.type()) || info.componentType() != FLOAT || info.normalized()) {
            throw error("BLENDLIB-X9-GLB-015", pointer,
                    "Accessor must be non-normalized FLOAT " + type);
        }
        return info;
    }

    private static AccessorInfo requireUv(GlbAccessorReader accessors, int accessor, String pointer) {
        AccessorInfo info = accessors.info(accessor);
        if (!"VEC2".equals(info.type())
                || (info.componentType() != FLOAT
                && !(info.normalized()
                && (info.componentType() == UNSIGNED_BYTE || info.componentType() == UNSIGNED_SHORT)))
                || (info.componentType() == FLOAT && info.normalized())) {
            throw error("BLENDLIB-X9-GLB-015", pointer,
                    "UV accessor must be non-normalized FLOAT or normalized U8/U16 VEC2");
        }
        return info;
    }

    private static AccessorInfo requireColor(GlbAccessorReader accessors, int accessor, String pointer) {
        AccessorInfo info = accessors.info(accessor);
        if (!("VEC3".equals(info.type()) || "VEC4".equals(info.type()))
                || (info.componentType() != FLOAT
                && !(info.normalized()
                && (info.componentType() == UNSIGNED_BYTE || info.componentType() == UNSIGNED_SHORT)))
                || (info.componentType() == FLOAT && info.normalized())) {
            throw error("BLENDLIB-X9-GLB-015", pointer,
                    "COLOR_0 must be non-normalized FLOAT or normalized U8/U16 VEC3/VEC4");
        }
        return info;
    }

    private static void readUv(GlbAccessorReader accessors, int accessor, AccessorInfo info) {
        if (info.componentType() == FLOAT) {
            accessors.readFloatElements(accessor, "VEC2");
        } else {
            accessors.readUnsignedElements(accessor, "VEC2");
        }
    }

    private static void readColor(GlbAccessorReader accessors, AccessorInfo info) {
        if (info.componentType() == FLOAT) {
            accessors.readFloatElements(info.index(), info.type());
        } else {
            accessors.readUnsignedElements(info.index(), info.type());
        }
    }

    private static void requireCapability(Set<BlendResourceId> capabilities, String capability, String pointer) {
        if (!capabilities.contains(BlendResourceId.parse(capability))) {
            throw error("BLENDLIB-X9-GLB-015", pointer,
                    "GLB feature is not declared by a negotiated X9 capability");
        }
    }

    private static int accessorIndex(JsonObject object, String field, String pointer) {
        return integer(required(object, field, pointer), pointer);
    }

    private static int boundedSum(int current, int increment, int maximum, String pointer, String message) {
        long result = (long) current + increment;
        if (result > maximum) {
            throw error("BLENDLIB-X9-LIMIT-001", pointer, message);
        }
        return (int) result;
    }

    private static long addBounded(long current, long increment, long maximum, String pointer, String message) {
        long result;
        try {
            result = Math.addExact(current, increment);
        } catch (ArithmeticException exception) {
            throw error("BLENDLIB-X9-LIMIT-001", pointer, message);
        }
        if (result > maximum) {
            throw error("BLENDLIB-X9-LIMIT-001", pointer, message);
        }
        return result;
    }

    private static JsonValue required(JsonObject object, String key, String pointer) {
        JsonValue value = object.get(key);
        if (value == null) {
            throw error("BLENDLIB-X9-GLB-002", pointer, "Required GLB field is missing");
        }
        return value;
    }

    private static void rejectUnknown(JsonObject object, Set<String> allowed, String pointer) {
        for (String key : object.values().keySet()) {
            if (!allowed.contains(key)) {
                throw error("BLENDLIB-X9-GLB-015", pointer + "/" + escape(key),
                        "Unknown field is outside the strict X9 feature envelope");
            }
        }
    }

    private static JsonObject object(JsonValue value, String pointer) {
        if (value instanceof JsonObject object) {
            return object;
        }
        throw error("BLENDLIB-X9-GLB-002", pointer, "Expected a JSON object");
    }

    private static JsonArray array(JsonValue value, String pointer) {
        if (value instanceof JsonArray array) {
            return array;
        }
        throw error("BLENDLIB-X9-GLB-002", pointer, "Expected a JSON array");
    }

    private static String string(JsonValue value, String pointer) {
        if (value instanceof JsonString string) {
            return string.value();
        }
        throw error("BLENDLIB-X9-GLB-015", pointer, "Expected a JSON string");
    }

    private static JsonNumber number(JsonValue value, String pointer) {
        if (value instanceof JsonNumber number) {
            return number;
        }
        throw error("BLENDLIB-X9-GLB-015", pointer, "Expected a JSON number");
    }

    private static int integer(JsonValue value, String pointer) {
        try {
            return number(value, pointer).asIntExact();
        } catch (IllegalArgumentException exception) {
            throw error("BLENDLIB-X9-GLB-015", pointer, "Expected a 32-bit integer");
        }
    }

    private static double finite(JsonNumber value, String pointer) {
        try {
            double result = value.asDouble();
            if (!Double.isFinite(result)) {
                throw new IllegalArgumentException("non-finite");
            }
            return result;
        } catch (IllegalArgumentException exception) {
            throw error("BLENDLIB-X9-GLB-015", pointer, "Expected a finite number");
        }
    }

    private static String escape(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private static ExperimentalProfileValidationException error(String code, String location, String message) {
        return new ExperimentalProfileValidationException(new ExperimentalProfileDiagnostic(
                ExperimentalProfileDiagnostic.Severity.ERROR,
                code,
                location,
                message,
                OptionalCapabilityFallback.MISSING_MODEL.serializedName()));
    }

    record Result(
            int primitiveCount,
            int morphTargetCount,
            int cubicSplineSamplerCount,
            int vertexColorPrimitiveCount,
            int secondaryUvPrimitiveCount) {
    }

    private record MeshInfo(int targetCount, int maxJointIndex) {
    }

    private record MeshResult(
            int primitives,
            int morphTargets,
            int vertexColorPrimitives,
            int secondaryUvPrimitives,
            List<MeshInfo> meshes) {
    }

    private record SamplerInfo(int outputAccessor, String interpolation, int inputCount) {
    }
}
