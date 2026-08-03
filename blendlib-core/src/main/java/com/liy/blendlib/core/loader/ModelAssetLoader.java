package com.liy.blendlib.core.loader;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.AnimationChannel;
import com.liy.blendlib.core.animation.AnimationClip;
import com.liy.blendlib.core.animation.AnimationPath;
import com.liy.blendlib.core.animation.Interpolation;
import com.liy.blendlib.core.asset.AssetBytes;
import com.liy.blendlib.core.asset.AssetResolver;
import com.liy.blendlib.core.descriptor.DescriptorDecoder;
import com.liy.blendlib.core.descriptor.ModelDescriptor;
import com.liy.blendlib.core.diagnostic.BlendAssetLoadException;
import com.liy.blendlib.core.diagnostic.BlendDiagnostic;
import com.liy.blendlib.core.diagnostic.BlendDiagnosticCodes;
import com.liy.blendlib.core.diagnostic.DiagnosticSeverity;
import com.liy.blendlib.core.glb.GlbAccessorReader;
import com.liy.blendlib.core.glb.GlbDocument;
import com.liy.blendlib.core.glb.GlbReader;
import com.liy.blendlib.core.json.JsonArray;
import com.liy.blendlib.core.json.JsonBoolean;
import com.liy.blendlib.core.json.JsonNumber;
import com.liy.blendlib.core.json.JsonObject;
import com.liy.blendlib.core.json.JsonString;
import com.liy.blendlib.core.json.JsonValue;
import com.liy.blendlib.core.limits.BlendAssetLimits;
import com.liy.blendlib.core.model.Bounds;
import com.liy.blendlib.core.model.Matrix4;
import com.liy.blendlib.core.model.MeshPrimitive;
import com.liy.blendlib.core.model.ModelAsset;
import com.liy.blendlib.core.model.ModelNode;
import com.liy.blendlib.core.model.ModelPrimitive;
import com.liy.blendlib.core.model.ModelProfile;
import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.Skeleton;
import com.liy.blendlib.core.model.Skin;
import com.liy.blendlib.core.model.SocketTable;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Strict pure-Java v1 descriptor plus GLB loader.
 *
 * <p>The loader only consumes caller-supplied {@link AssetBytes}; it owns no
 * file system, URI, Minecraft, Fabric, thread, or rendering dependency. All
 * returned state is immutable and no JSON/GLB parsing occurs after this method
 * returns.</p>
 */
public final class ModelAssetLoader {
    private final BlendAssetLimits limits;
    private final DescriptorDecoder descriptorDecoder;
    private final GlbReader glbReader;

    public ModelAssetLoader() {
        this(BlendAssetLimits.DEFAULT);
    }

    public ModelAssetLoader(BlendAssetLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.descriptorDecoder = new DescriptorDecoder(limits);
        this.glbReader = new GlbReader(limits);
    }

    /** Loads generation zero for callers that do not yet manage a reload registry. */
    public ModelAsset load(BlendResourceId modelKey, AssetBytes descriptorBytes, AssetResolver resolver) {
        return load(modelKey, 0L, descriptorBytes, resolver);
    }

    /** Decodes a descriptor and its resolved GLB into one immutable resource generation. */
    public ModelAsset load(BlendResourceId modelKey, long generation, AssetBytes descriptorBytes, AssetResolver resolver) {
        Objects.requireNonNull(modelKey, "modelKey");
        Objects.requireNonNull(descriptorBytes, "descriptorBytes");
        Objects.requireNonNull(resolver, "resolver");
        ModelDescriptor descriptor = descriptorDecoder.decode(modelKey, descriptorBytes);
        AssetBytes glbBytes;
        try {
            glbBytes = resolver.resolve(descriptor.meshId());
        } catch (BlendAssetLoadException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw LoaderFailure.error(BlendDiagnosticCodes.DESC_002, modelKey, descriptor.descriptorId(), "/mesh",
                    "Unable to resolve the descriptor mesh resource", exception);
        }
        if (glbBytes == null || !descriptor.meshId().equals(glbBytes.resourceId())) {
            throw LoaderFailure.error(BlendDiagnosticCodes.DESC_002, modelKey, descriptor.descriptorId(), "/mesh",
                    "Resolver must return bytes for the exact descriptor mesh resource");
        }
        return decode(modelKey, generation, descriptor, glbBytes);
    }

    /** Decodes a prevalidated descriptor with an already-resolved strict GLB archive. */
    public ModelAsset decode(BlendResourceId modelKey, long generation, ModelDescriptor descriptor, AssetBytes glbBytes) {
        Objects.requireNonNull(modelKey, "modelKey");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(glbBytes, "glbBytes");
        if (generation < 0) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
        if (!descriptor.meshId().equals(glbBytes.resourceId())) {
            throw LoaderFailure.error(BlendDiagnosticCodes.DESC_002, modelKey, descriptor.descriptorId(), "/mesh",
                    "GLB bytes do not match the descriptor mesh resource");
        }
        GlbDocument document = glbReader.read(modelKey, glbBytes);
        return new Decoder(modelKey, generation, descriptor, glbBytes.resourceId(), document, limits).decode();
    }

    private static final class Decoder {
        private static final int VERTEX_WARNING_THRESHOLD = 100_000;
        private static final int SKIN_JOINT_WARNING_THRESHOLD = 128;

        private final BlendResourceId modelKey;
        private final long generation;
        private final ModelDescriptor descriptor;
        private final BlendResourceId glbResourceId;
        private final GlbDocument document;
        private final BlendAssetLimits limits;
        private final JsonObject root;
        private final GlbAccessorReader accessors;
        private final List<BlendDiagnostic> diagnostics = new ArrayList<>();

        Decoder(
                BlendResourceId modelKey,
                long generation,
                ModelDescriptor descriptor,
                BlendResourceId glbResourceId,
                GlbDocument document,
                BlendAssetLimits limits) {
            this.modelKey = modelKey;
            this.generation = generation;
            this.descriptor = descriptor;
            this.glbResourceId = glbResourceId;
            this.document = document;
            this.limits = limits;
            this.root = document.json();
            this.accessors = new GlbAccessorReader(modelKey, glbResourceId, document, limits);
        }

        ModelAsset decode() {
            validateAssetMetadata();
            validateRequiredExtensions();
            validateUnsupportedRootFeatures();
            accessors.validateAll();
            List<String> materialNames = decodeMaterialNames();
            List<List<MeshPrimitive>> meshes = decodeMeshes(materialNames);
            List<NodeData> nodeData = decodeNodes(meshes.size());
            SceneData scene = validateSceneAndBuildWorldTransforms(nodeData);
            Skeleton skeleton = decodeSkins(nodeData, scene.parents());
            validateAllNodeSkinReferences(nodeData, skeleton);
            emitSkinPerformanceWarning(nodeData, skeleton);
            List<ModelPrimitive> primitives = bindPrimitives(meshes, nodeData, scene.worldTransforms(), skeleton);
            Bounds bounds = calculateBounds(primitives, scene.worldTransforms());
            List<AnimationClip> clips = decodeAnimations(nodeData);
            validateDescriptorClipReferences(clips);
            validateCanonicalActiveHierarchy(nodeData, scene, skeleton, primitives, clips);
            SocketTable sockets = decodeSockets(nodeData, scene.rootNodes());
            List<ModelNode> publicNodes = nodeData.stream()
                    .map(node -> new ModelNode(node.index(), node.name(), node.localTransform(), node.children(), node.meshIndex(),
                            node.skinIndex(), node.cameraOrLightIgnored()))
                    .toList();
            try {
                return new ModelAsset(modelKey, descriptor.descriptorId(), generation, descriptor.profile(), descriptor.unitsPerBlock(),
                        descriptor.materials(), descriptor.animation(), publicNodes, scene.rootNodes(), primitives, skeleton, clips, sockets, bounds,
                        diagnostics);
            } catch (IllegalArgumentException exception) {
                String boundsLocation = clips.isEmpty() && skeleton != null ? "/skins" : "/animations";
                throw fail(BlendDiagnosticCodes.LIMIT_001, boundsLocation,
                        "Animated culling bounds exceed the finite strict-v1 preparation envelope", exception);
            }
        }

        private void validateAssetMetadata() {
            JsonObject asset = object(required(root, "asset", "/asset", BlendDiagnosticCodes.GLB_002), "/asset",
                    BlendDiagnosticCodes.GLB_002);
            String version = string(required(asset, "version", "/asset/version", BlendDiagnosticCodes.GLB_002), "/asset/version",
                    BlendDiagnosticCodes.GLB_002);
            if (!"2.0".equals(version)) {
                throw fail(BlendDiagnosticCodes.GLB_002, "/asset/version", "Strict v1 requires glTF asset version 2.0");
            }
        }

        private void validateRequiredExtensions() {
            JsonValue required = root.get("extensionsRequired");
            if (required == null) {
                return;
            }
            JsonArray extensions = array(required, "/extensionsRequired", BlendDiagnosticCodes.GLB_002);
            if (extensions.size() > 0) {
                throw fail(BlendDiagnosticCodes.EXT_001, "/extensionsRequired", "GLB declares an unsupported required extension");
            }
        }

        private void validateUnsupportedRootFeatures() {
            for (String feature : List.of("images", "textures", "samplers")) {
                JsonValue value = root.get(feature);
                if (value == null) {
                    continue;
                }
                JsonArray entries = array(value, "/" + feature, BlendDiagnosticCodes.GLB_015);
                if (entries.size() > 0) {
                    throw fail(BlendDiagnosticCodes.GLB_015, "/" + feature,
                            "Embedded image and texture material data are not supported by v1");
                }
            }
        }

        private List<String> decodeMaterialNames() {
            JsonArray materials = array(required(root, "materials", "/materials", BlendDiagnosticCodes.GLB_002), "/materials",
                    BlendDiagnosticCodes.GLB_002);
            if (materials.size() == 0) {
                throw fail(BlendDiagnosticCodes.GLB_015, "/materials", "Strict v1 GLB must declare material slots");
            }
            if (materials.size() > limits.maxMaterialSlots()) {
                throw fail(BlendDiagnosticCodes.LIMIT_001, "/materials", "GLB material-slot limit exceeded");
            }
            List<String> names = new ArrayList<>();
            Set<String> unique = new HashSet<>();
            for (int index = 0; index < materials.size(); index++) {
                String pointer = "/materials/" + index;
                JsonObject material = object(materials.get(index), pointer, BlendDiagnosticCodes.GLB_015);
                if (material.containsKey("extensions") && !isEmptyObject(material.get("extensions"), pointer + "/extensions")) {
                    throw fail(BlendDiagnosticCodes.GLB_015, pointer + "/extensions", "Material extensions are not supported by v1");
                }
                String name = string(required(material, "name", pointer + "/name", BlendDiagnosticCodes.GLB_015), pointer + "/name",
                        BlendDiagnosticCodes.GLB_015);
                if (name.isBlank() || !unique.add(name)) {
                    throw fail(BlendDiagnosticCodes.GLB_015, pointer + "/name", "GLB material names must be unique and non-blank");
                }
                if (!descriptor.materials().containsKey(name)) {
                    throw fail(BlendDiagnosticCodes.MAT_003, pointer + "/name", "GLB material slot has no descriptor mapping");
                }
                names.add(name);
            }
            return List.copyOf(names);
        }

        private List<List<MeshPrimitive>> decodeMeshes(List<String> materialNames) {
            JsonArray meshArray = array(required(root, "meshes", "/meshes", BlendDiagnosticCodes.GLB_002), "/meshes",
                    BlendDiagnosticCodes.GLB_002);
            if (meshArray.size() == 0) {
                throw fail(BlendDiagnosticCodes.GLB_015, "/meshes", "GLB must contain at least one mesh");
            }
            long vertices = 0;
            long indices = 0;
            List<List<MeshPrimitive>> meshes = new ArrayList<>();
            for (int meshIndex = 0; meshIndex < meshArray.size(); meshIndex++) {
                String pointer = "/meshes/" + meshIndex;
                JsonObject mesh = object(meshArray.get(meshIndex), pointer, BlendDiagnosticCodes.GLB_015);
                if (mesh.containsKey("weights")) {
                    throw fail(BlendDiagnosticCodes.GLB_015, pointer + "/weights", "Morph targets are not supported by v1");
                }
                JsonArray primitiveArray = array(required(mesh, "primitives", pointer + "/primitives", BlendDiagnosticCodes.GLB_015),
                        pointer + "/primitives", BlendDiagnosticCodes.GLB_015);
                if (primitiveArray.size() == 0) {
                    throw fail(BlendDiagnosticCodes.GLB_015, pointer + "/primitives", "Mesh must contain a primitive");
                }
                List<MeshPrimitive> primitiveList = new ArrayList<>();
                for (int primitiveIndex = 0; primitiveIndex < primitiveArray.size(); primitiveIndex++) {
                    String primitivePointer = pointer + "/primitives/" + primitiveIndex;
                    JsonObject primitive = object(primitiveArray.get(primitiveIndex), primitivePointer, BlendDiagnosticCodes.GLB_015);
                    if (primitive.containsKey("targets")) {
                        throw fail(BlendDiagnosticCodes.GLB_015, primitivePointer + "/targets", "Morph targets are not supported by v1");
                    }
                    if (primitive.containsKey("extensions") && !isEmptyObject(primitive.get("extensions"), primitivePointer + "/extensions")) {
                        throw fail(BlendDiagnosticCodes.GLB_015, primitivePointer + "/extensions",
                                "Primitive extensions are not supported by v1");
                    }
                    int mode = primitive.containsKey("mode")
                            ? integer(primitive.get("mode"), primitivePointer + "/mode", BlendDiagnosticCodes.GLB_015)
                            : 4;
                    if (mode != 4) {
                        throw fail(BlendDiagnosticCodes.GLB_015, primitivePointer + "/mode", "Only TRIANGLES primitive mode is supported");
                    }
                    JsonObject attributes = object(required(primitive, "attributes", primitivePointer + "/attributes", BlendDiagnosticCodes.GLB_015),
                            primitivePointer + "/attributes", BlendDiagnosticCodes.GLB_015);
                    validatePrimitiveAttributeNames(attributes, primitivePointer + "/attributes");
                    int positionAccessor = accessorIndex(attributes, "POSITION", primitivePointer + "/attributes/POSITION");
                    int normalAccessor = accessorIndex(attributes, "NORMAL", primitivePointer + "/attributes/NORMAL");
                    int uvAccessor = accessorIndex(attributes, "TEXCOORD_0", primitivePointer + "/attributes/TEXCOORD_0");
                    int indexAccessor = integer(required(primitive, "indices", primitivePointer + "/indices", BlendDiagnosticCodes.GLB_015),
                            primitivePointer + "/indices", BlendDiagnosticCodes.GLB_015);
                    int materialIndex = integer(required(primitive, "material", primitivePointer + "/material", BlendDiagnosticCodes.GLB_015),
                            primitivePointer + "/material", BlendDiagnosticCodes.GLB_015);
                    if (materialIndex < 0 || materialIndex >= materialNames.size()) {
                        throw fail(BlendDiagnosticCodes.GLB_015, primitivePointer + "/material", "Primitive material index is invalid");
                    }
                    int vertexCount = preflightPrimitiveCounts(positionAccessor, normalAccessor, uvAccessor, indexAccessor, vertices, indices,
                            primitivePointer);
                    float[] positions = accessors.readFloatElements(positionAccessor, "VEC3");
                    float[] normals = accessors.readFloatElements(normalAccessor, "VEC3");
                    float[] texCoords = accessors.readFloatElements(uvAccessor, "VEC2");
                    int[] triangleIndices = accessors.readIndexElements(indexAccessor);
                    if (positions.length / 3 != vertexCount || normals.length / 3 != vertexCount || texCoords.length / 2 != vertexCount
                            || triangleIndices.length == 0 || triangleIndices.length % 3 != 0) {
                        throw fail(BlendDiagnosticCodes.GLB_015, primitivePointer, "Primitive attribute or index cardinality is invalid");
                    }
                    for (int value : triangleIndices) {
                        if (value < 0 || value >= vertexCount) {
                            throw fail(BlendDiagnosticCodes.GLB_015, primitivePointer + "/indices",
                                    "Primitive index references a missing vertex");
                        }
                    }
                    vertices += vertexCount;
                    indices += triangleIndices.length;
                    int[] joints = null;
                    float[] weights = null;
                    if (descriptor.profile() == ModelProfile.SKINNED_V1) {
                        int jointAccessor = accessorIndex(attributes, "JOINTS_0", primitivePointer + "/attributes/JOINTS_0");
                        int weightAccessor = accessorIndex(attributes, "WEIGHTS_0", primitivePointer + "/attributes/WEIGHTS_0");
                        var jointInfo = accessors.requireUnnormalized(jointAccessor, "JOINTS_0 accessor");
                        var weightInfo = accessors.info(weightAccessor);
                        if (jointInfo.count() != vertexCount || weightInfo.count() != vertexCount) {
                            throw fail(BlendDiagnosticCodes.SKIN_001, primitivePointer + "/attributes",
                                    "Skin attribute counts must match POSITION before decoding");
                        }
                        int componentType = jointInfo.componentType();
                        if (componentType != 5121 && componentType != 5123) {
                            throw fail(BlendDiagnosticCodes.SKIN_001, primitivePointer + "/attributes/JOINTS_0",
                                    "Skin joints must use U8 or U16 components");
                        }
                        joints = accessors.readUnsignedElements(jointAccessor, "VEC4");
                        weights = accessors.readWeightElements(weightAccessor);
                        validateVertexInfluences(joints, weights, primitivePointer);
                    }
                    try {
                        primitiveList.add(new MeshPrimitive(materialNames.get(materialIndex), positions, normals, texCoords, triangleIndices, joints,
                                weights));
                    } catch (IllegalArgumentException exception) {
                        String code = descriptor.profile() == ModelProfile.SKINNED_V1 ? BlendDiagnosticCodes.SKIN_001 : BlendDiagnosticCodes.GLB_015;
                        throw fail(code, primitivePointer, "Primitive data violates the strict v1 profile", exception);
                    }
                }
                meshes.add(List.copyOf(primitiveList));
            }
            if (vertices > VERTEX_WARNING_THRESHOLD) {
                diagnostics.add(new BlendDiagnostic(DiagnosticSeverity.WARN, BlendDiagnosticCodes.PERF_001, modelKey, glbResourceId,
                        "/meshes", "Asset exceeds the non-fatal vertex performance-warning threshold", ""));
            }
            return List.copyOf(meshes);
        }

        private void validatePrimitiveAttributeNames(JsonObject attributes, String pointer) {
            Set<String> allowed = descriptor.profile() == ModelProfile.SKINNED_V1
                    ? Set.of("POSITION", "NORMAL", "TEXCOORD_0", "JOINTS_0", "WEIGHTS_0")
                    : Set.of("POSITION", "NORMAL", "TEXCOORD_0");
            for (String attribute : attributes.values().keySet()) {
                if (!allowed.contains(attribute)) {
                    throw fail(BlendDiagnosticCodes.GLB_015, pointer + "/" + attribute,
                            "Unsupported vertex attribute in strict v1 profile");
                }
            }
        }

        private int preflightPrimitiveCounts(
                int positionAccessor,
                int normalAccessor,
                int uvAccessor,
                int indexAccessor,
                long currentVertices,
                long currentIndices,
                String primitivePointer) {
            var position = accessors.requireMinMax(positionAccessor);
            var normal = accessors.info(normalAccessor);
            var uv = accessors.info(uvAccessor);
            var index = accessors.requireUnnormalized(indexAccessor, "Triangle index accessor");
            if (!"VEC3".equals(position.type()) || position.componentType() != 5126 || !"VEC3".equals(normal.type())
                    || normal.componentType() != 5126 || !"VEC2".equals(uv.type()) || uv.componentType() != 5126
                    || !"SCALAR".equals(index.type())) {
                throw fail(BlendDiagnosticCodes.GLB_015, primitivePointer, "Primitive accessor type is unsupported by v1");
            }
            if (position.count() == 0 || normal.count() != position.count() || uv.count() != position.count() || index.count() == 0
                    || index.count() % 3 != 0) {
                throw fail(BlendDiagnosticCodes.GLB_015, primitivePointer, "Primitive accessor cardinality is invalid");
            }
            addBounded(currentVertices, position.count(), limits.maxVertices(), primitivePointer + "/attributes/POSITION", "Vertex limit exceeded");
            addBounded(currentIndices, index.count(), limits.maxIndices(), primitivePointer + "/indices", "Index limit exceeded");
            return position.count();
        }

        private List<NodeData> decodeNodes(int meshCount) {
            JsonArray nodes = array(required(root, "nodes", "/nodes", BlendDiagnosticCodes.GLB_002), "/nodes", BlendDiagnosticCodes.GLB_002);
            if (nodes.size() == 0) {
                throw fail(BlendDiagnosticCodes.SCENE_005, "/nodes", "GLB must contain scene nodes");
            }
            if (nodes.size() > limits.maxNodes()) {
                throw fail(BlendDiagnosticCodes.LIMIT_001, "/nodes", "Node limit exceeded");
            }
            if (descriptor.profile() == ModelProfile.RIGID_V1 && nodes.size() > limits.maxRigidNodes()) {
                throw fail(BlendDiagnosticCodes.LIMIT_001, "/nodes", "Rigid-node limit exceeded");
            }
            List<NodeData> result = new ArrayList<>();
            for (int index = 0; index < nodes.size(); index++) {
                String pointer = "/nodes/" + index;
                JsonObject node = object(nodes.get(index), pointer, BlendDiagnosticCodes.SCENE_005);
                int meshIndex = node.containsKey("mesh") ? integer(node.get("mesh"), pointer + "/mesh", BlendDiagnosticCodes.SCENE_005) : -1;
                if (meshIndex < 0 && node.containsKey("mesh")) {
                    throw fail(BlendDiagnosticCodes.SCENE_005, pointer + "/mesh", "Node mesh reference is invalid");
                }
                if (meshIndex < -1 || meshIndex >= meshCount) {
                    throw fail(BlendDiagnosticCodes.SCENE_005, pointer + "/mesh", "Node mesh reference is invalid");
                }
                int skinIndex = node.containsKey("skin") ? integer(node.get("skin"), pointer + "/skin", BlendDiagnosticCodes.SCENE_005) : -1;
                if (skinIndex < 0 && node.containsKey("skin")) {
                    throw fail(BlendDiagnosticCodes.SCENE_005, pointer + "/skin", "Node skin reference is invalid");
                }
                if (skinIndex < -1) {
                    throw fail(BlendDiagnosticCodes.SCENE_005, pointer + "/skin", "Node skin reference is invalid");
                }
                List<Integer> children = node.containsKey("children")
                        ? integerList(array(node.get("children"), pointer + "/children", BlendDiagnosticCodes.SCENE_005), pointer + "/children",
                                BlendDiagnosticCodes.SCENE_005)
                        : List.of();
                if (new HashSet<>(children).size() != children.size()) {
                    throw fail(BlendDiagnosticCodes.SCENE_005, pointer + "/children", "Node child references must be unique");
                }
                String name = node.containsKey("name") ? string(node.get("name"), pointer + "/name", BlendDiagnosticCodes.SCENE_005) : "node-" + index;
                if (name.isBlank()) {
                    throw fail(BlendDiagnosticCodes.SCENE_005, pointer + "/name", "Node name must not be blank");
                }
                boolean matrixDeclared = node.containsKey("matrix");
                Transform transform = decodeNodeTransform(node, pointer);
                boolean cameraOrLight = validateCameraOrLight(node, pointer);
                if (node.containsKey("weights")) {
                    throw fail(BlendDiagnosticCodes.GLB_015, pointer + "/weights", "Node morph weights are not supported by v1");
                }
                result.add(new NodeData(index, name, transform, children, meshIndex, skinIndex, cameraOrLight, matrixDeclared));
            }
            return List.copyOf(result);
        }

        private Transform decodeNodeTransform(JsonObject node, String pointer) {
            boolean hasMatrix = node.containsKey("matrix");
            boolean hasTrs = node.containsKey("translation") || node.containsKey("rotation") || node.containsKey("scale");
            if (hasMatrix && hasTrs) {
                throw fail(BlendDiagnosticCodes.SCENE_005, pointer, "Node matrix cannot be combined with TRS fields");
            }
            try {
                if (hasMatrix) {
                    return new Matrix4(floatArray(array(node.get("matrix"), pointer + "/matrix", BlendDiagnosticCodes.SCENE_005), 16,
                            pointer + "/matrix", BlendDiagnosticCodes.SCENE_005)).decomposeTrs();
                }
                Vec3 translation = node.containsKey("translation")
                        ? vec3(node.get("translation"), pointer + "/translation")
                        : Vec3.ZERO;
                Quaternion rotation = node.containsKey("rotation")
                        ? quaternion(node.get("rotation"), pointer + "/rotation")
                        : Quaternion.IDENTITY;
                Vec3 scale = node.containsKey("scale") ? vec3(node.get("scale"), pointer + "/scale") : Vec3.ONE;
                return new Transform(translation, rotation, scale);
            } catch (BlendAssetLoadException exception) {
                throw exception;
            } catch (IllegalArgumentException exception) {
                throw fail(BlendDiagnosticCodes.SCENE_005, pointer, "Node transform is invalid or non-finite", exception);
            }
        }

        private boolean validateCameraOrLight(JsonObject node, String pointer) {
            boolean ignored = false;
            if (node.containsKey("camera")) {
                int camera = integer(node.get("camera"), pointer + "/camera", BlendDiagnosticCodes.SCENE_005);
                JsonArray cameras = array(required(root, "cameras", "/cameras", BlendDiagnosticCodes.SCENE_005), "/cameras",
                        BlendDiagnosticCodes.SCENE_005);
                if (camera < 0 || camera >= cameras.size()) {
                    throw fail(BlendDiagnosticCodes.SCENE_005, pointer + "/camera", "Node camera reference is invalid");
                }
                ignored = true;
            }
            if (node.containsKey("extensions")) {
                JsonObject extensions = object(node.get("extensions"), pointer + "/extensions", BlendDiagnosticCodes.GLB_015);
                for (String key : extensions.values().keySet()) {
                    if (!"KHR_lights_punctual".equals(key)) {
                        throw fail(BlendDiagnosticCodes.GLB_015, pointer + "/extensions/" + key,
                                "Node extension is not supported by v1");
                    }
                    JsonObject light = object(extensions.get(key), pointer + "/extensions/" + key, BlendDiagnosticCodes.GLB_015);
                    int lightIndex = integer(required(light, "light", pointer + "/extensions/" + key + "/light", BlendDiagnosticCodes.GLB_015),
                            pointer + "/extensions/" + key + "/light", BlendDiagnosticCodes.GLB_015);
                    JsonObject rootExtensions = object(required(root, "extensions", "/extensions", BlendDiagnosticCodes.GLB_015), "/extensions",
                            BlendDiagnosticCodes.GLB_015);
                    JsonObject punctual = object(required(rootExtensions, "KHR_lights_punctual", "/extensions/KHR_lights_punctual",
                            BlendDiagnosticCodes.GLB_015), "/extensions/KHR_lights_punctual", BlendDiagnosticCodes.GLB_015);
                    JsonArray lights = array(required(punctual, "lights", "/extensions/KHR_lights_punctual/lights", BlendDiagnosticCodes.GLB_015),
                            "/extensions/KHR_lights_punctual/lights", BlendDiagnosticCodes.GLB_015);
                    if (lightIndex < 0 || lightIndex >= lights.size()) {
                        throw fail(BlendDiagnosticCodes.SCENE_005, pointer + "/extensions/" + key + "/light",
                                "Node light reference is invalid");
                    }
                    ignored = true;
                }
            }
            if (ignored) {
                diagnostics.add(new BlendDiagnostic(DiagnosticSeverity.WARN, BlendDiagnosticCodes.SCENE_006, modelKey, glbResourceId,
                        pointer, "Camera or light node is ignored by the v1 profile", ""));
            }
            return ignored;
        }

        private SceneData validateSceneAndBuildWorldTransforms(List<NodeData> nodes) {
            for (NodeData node : nodes) {
                for (int child : node.children()) {
                    if (child < 0 || child >= nodes.size()) {
                        throw fail(BlendDiagnosticCodes.SCENE_005, "/nodes/" + node.index() + "/children",
                                "Node child reference is invalid");
                    }
                }
            }
            int[] parents = validateUniqueParents(nodes);
            validateAcyclicAndDepthBounded(nodes, parents);
            JsonArray scenes = array(required(root, "scenes", "/scenes", BlendDiagnosticCodes.SCENE_005), "/scenes",
                    BlendDiagnosticCodes.SCENE_005);
            int sceneIndex = integer(required(root, "scene", "/scene", BlendDiagnosticCodes.SCENE_005), "/scene", BlendDiagnosticCodes.SCENE_005);
            if (sceneIndex < 0 || sceneIndex >= scenes.size()) {
                throw fail(BlendDiagnosticCodes.SCENE_005, "/scene", "Default scene reference is invalid");
            }
            JsonObject scene = object(scenes.get(sceneIndex), "/scenes/" + sceneIndex, BlendDiagnosticCodes.SCENE_005);
            List<Integer> roots = integerList(array(required(scene, "nodes", "/scenes/" + sceneIndex + "/nodes", BlendDiagnosticCodes.SCENE_005),
                    "/scenes/" + sceneIndex + "/nodes", BlendDiagnosticCodes.SCENE_005), "/scenes/" + sceneIndex + "/nodes",
                    BlendDiagnosticCodes.SCENE_005);
            if (roots.isEmpty() || new HashSet<>(roots).size() != roots.size()) {
                throw fail(BlendDiagnosticCodes.SCENE_005, "/scenes/" + sceneIndex + "/nodes", "Scene roots must be non-empty and unique");
            }
            Transform[] world = new Transform[nodes.size()];
            ArrayDeque<Integer> pending = new ArrayDeque<>();
            for (int rootIndex : roots) {
                if (rootIndex < 0 || rootIndex >= nodes.size()) {
                    throw fail(BlendDiagnosticCodes.SCENE_005, "/scenes/" + sceneIndex + "/nodes", "Scene root reference is invalid");
                }
                world[rootIndex] = nodes.get(rootIndex).localTransform();
                pending.add(rootIndex);
            }
            while (!pending.isEmpty()) {
                int parent = pending.removeFirst();
                for (int child : nodes.get(parent).children()) {
                    if (world[child] != null) {
                        throw fail(BlendDiagnosticCodes.SCENE_005, "/nodes/" + parent + "/children", "Node has multiple active parents");
                    }
                    try {
                        world[child] = world[parent].compose(nodes.get(child).localTransform());
                    } catch (IllegalArgumentException exception) {
                        throw fail(BlendDiagnosticCodes.SCENE_005, "/nodes/" + child,
                                "Composed scene transform is invalid or non-finite", exception);
                    }
                    pending.addLast(child);
                }
            }
            return new SceneData(List.copyOf(roots), world, parents);
        }

        private int[] validateUniqueParents(List<NodeData> nodes) {
            int[] parents = new int[nodes.size()];
            Arrays.fill(parents, -1);
            for (NodeData node : nodes) {
                for (int child : node.children()) {
                    if (parents[child] != -1) {
                        throw fail(BlendDiagnosticCodes.SCENE_005, "/nodes/" + node.index() + "/children",
                                "A strict v1 node may have only one parent");
                    }
                    parents[child] = node.index();
                }
            }
            return parents;
        }

        private void validateAcyclicAndDepthBounded(List<NodeData> nodes, int[] parents) {
            byte[] colors = new byte[nodes.size()];
            for (int start = 0; start < nodes.size(); start++) {
                if (colors[start] != 0) {
                    continue;
                }
                ArrayDeque<TraversalFrame> stack = new ArrayDeque<>();
                colors[start] = 1;
                stack.push(new TraversalFrame(start, 0));
                while (!stack.isEmpty()) {
                    TraversalFrame frame = stack.peek();
                    List<Integer> children = nodes.get(frame.node()).children();
                    if (frame.nextChild() == children.size()) {
                        colors[frame.node()] = 2;
                        stack.pop();
                        continue;
                    }
                    int child = children.get(frame.nextChild());
                    frame.advance();
                    if (colors[child] == 1) {
                        throw fail(BlendDiagnosticCodes.SCENE_004, "/nodes/" + frame.node() + "/children", "Node hierarchy cycle detected");
                    }
                    if (colors[child] == 0) {
                        colors[child] = 1;
                        stack.push(new TraversalFrame(child, 0));
                    }
                }
            }
            validateDepthFromRoots(nodes, parents);
        }

        /**
         * Calculates depth from the structural root instead of traversal order. This is separate
         * from cycle detection because a reverse-numbered tree can finish its leaf subtree before
         * the actual root is examined.
         */
        private void validateDepthFromRoots(List<NodeData> nodes, int[] parents) {
            for (int root = 0; root < nodes.size(); root++) {
                if (parents[root] != -1) {
                    continue;
                }
                ArrayDeque<NodeDepth> pending = new ArrayDeque<>();
                pending.addLast(new NodeDepth(root, 1));
                while (!pending.isEmpty()) {
                    NodeDepth current = pending.removeFirst();
                    if (current.depth() > limits.maxHierarchyDepth()) {
                        throw fail(BlendDiagnosticCodes.LIMIT_001, "/nodes/" + current.node(),
                                "Node hierarchy depth limit exceeded");
                    }
                    for (int child : nodes.get(current.node()).children()) {
                        pending.addLast(new NodeDepth(child, current.depth() + 1));
                    }
                }
            }
        }

        private Skeleton decodeSkins(List<NodeData> nodes, int[] parents) {
            JsonValue value = root.get("skins");
            JsonArray skins = value == null ? new JsonArray(List.of()) : array(value, "/skins", BlendDiagnosticCodes.SKIN_001);
            // Apply hard limits before profile-specific rejection so a hostile rigid archive cannot
            // bypass the frozen safety diagnostic by merely selecting the wrong descriptor profile.
            for (int skinIndex = 0; skinIndex < skins.size(); skinIndex++) {
                String pointer = "/skins/" + skinIndex;
                JsonObject skin = object(skins.get(skinIndex), pointer, BlendDiagnosticCodes.SKIN_001);
                if (skin.containsKey("joints")) {
                    JsonArray joints = array(skin.get("joints"), pointer + "/joints", BlendDiagnosticCodes.SKIN_001);
                    if (joints.size() > limits.maxSkinJoints()) {
                        throw fail(BlendDiagnosticCodes.LIMIT_001, pointer + "/joints", "Skin joint limit exceeded");
                    }
                }
            }
            if (descriptor.profile() == ModelProfile.RIGID_V1) {
                if (skins.size() > 0) {
                    throw fail(BlendDiagnosticCodes.SKIN_001, "/skins", "Rigid profile must not declare skins");
                }
                return null;
            }
            if (skins.size() == 0) {
                throw fail(BlendDiagnosticCodes.SKIN_001, "/skins", "Skinned profile requires at least one skin");
            }
            List<Skin> result = new ArrayList<>();
            for (int skinIndex = 0; skinIndex < skins.size(); skinIndex++) {
                String pointer = "/skins/" + skinIndex;
                JsonObject skin = object(skins.get(skinIndex), pointer, BlendDiagnosticCodes.SKIN_001);
                JsonArray jointArray = array(required(skin, "joints", pointer + "/joints", BlendDiagnosticCodes.SKIN_001), pointer + "/joints",
                        BlendDiagnosticCodes.SKIN_001);
                if (jointArray.size() > limits.maxSkinJoints()) {
                    throw fail(BlendDiagnosticCodes.LIMIT_001, pointer + "/joints", "Skin joint limit exceeded");
                }
                List<Integer> joints = integerList(jointArray, pointer + "/joints", BlendDiagnosticCodes.SKIN_001);
                if (joints.isEmpty()) {
                    throw fail(BlendDiagnosticCodes.SKIN_001, pointer + "/joints", "Skin must declare joints");
                }
                Set<Integer> uniqueJoints = new HashSet<>();
                for (int jointSlot = 0; jointSlot < joints.size(); jointSlot++) {
                    int joint = joints.get(jointSlot);
                    if (!uniqueJoints.add(joint)) {
                        throw fail(BlendDiagnosticCodes.SKIN_001, pointer + "/joints/" + jointSlot,
                                "Skin joints must be unique");
                    }
                    if (joint < 0 || joint >= nodes.size()) {
                        throw fail(BlendDiagnosticCodes.SKIN_001, pointer + "/joints/" + jointSlot,
                                "Skin joint node reference is invalid");
                    }
                }
                int commonRoot = closestCommonAncestor(joints, parents);
                if (commonRoot < 0) {
                    throw fail(BlendDiagnosticCodes.SKIN_001, pointer + "/joints",
                            "Skin joints must belong to one hierarchy with a common root");
                }
                int rootNode = skin.containsKey("skeleton")
                        ? integer(skin.get("skeleton"), pointer + "/skeleton", BlendDiagnosticCodes.SKIN_001)
                        : -1;
                if (rootNode < 0 && skin.containsKey("skeleton")) {
                    throw fail(BlendDiagnosticCodes.SKIN_001, pointer + "/skeleton", "Skin skeleton root is invalid");
                }
                if (rootNode >= nodes.size()) {
                    throw fail(BlendDiagnosticCodes.SKIN_001, pointer + "/skeleton", "Skin skeleton root is invalid");
                }
                if (rootNode >= 0 && !isAncestorOrSelf(rootNode, commonRoot, parents)) {
                    throw fail(BlendDiagnosticCodes.SKIN_001, pointer + "/skeleton",
                            "Skin skeleton must be the joints' closest common root or one of its ancestors");
                }
                int inverseBindAccessor = integer(required(skin, "inverseBindMatrices", pointer + "/inverseBindMatrices", BlendDiagnosticCodes.SKIN_001),
                        pointer + "/inverseBindMatrices", BlendDiagnosticCodes.SKIN_001);
                var inverseBindInfo = accessors.info(inverseBindAccessor);
                if (!"MAT4".equals(inverseBindInfo.type()) || inverseBindInfo.componentType() != 5126
                        || inverseBindInfo.count() != joints.size()) {
                    throw fail(BlendDiagnosticCodes.SKIN_001, pointer + "/inverseBindMatrices",
                            "Skin inverse-bind accessor must be FLOAT MAT4 with one matrix per joint");
                }
                float[] inverseBinds;
                try {
                    inverseBinds = accessors.readFloatElements(inverseBindAccessor, "MAT4");
                } catch (BlendAssetLoadException exception) {
                    throw exception;
                }
                if (inverseBinds.length != joints.size() * 16) {
                    throw fail(BlendDiagnosticCodes.SKIN_001, pointer + "/inverseBindMatrices",
                            "Skin inverse-bind matrix count does not match joints");
                }
                validateInverseBindMatrices(inverseBinds, pointer + "/inverseBindMatrices");
                String name = skin.containsKey("name") ? string(skin.get("name"), pointer + "/name", BlendDiagnosticCodes.SKIN_001) : "skin-" + skinIndex;
                try {
                    result.add(new Skin(name, rootNode, joints, inverseBinds));
                } catch (IllegalArgumentException exception) {
                    throw fail(BlendDiagnosticCodes.SKIN_001, pointer, "Skin data is invalid", exception);
                }
            }
            try {
                return new Skeleton(result);
            } catch (IllegalArgumentException exception) {
                throw fail(BlendDiagnosticCodes.SKIN_001, "/skins", "Skeleton data is invalid", exception);
            }
        }

        private List<ModelPrimitive> bindPrimitives(
                List<List<MeshPrimitive>> meshes, List<NodeData> nodes, Transform[] worldTransforms, Skeleton skeleton) {
            List<ModelPrimitive> result = new ArrayList<>();
            int[] bindings = new int[meshes.size()];
            for (NodeData node : nodes) {
                if (node.meshIndex() < 0) {
                    continue;
                }
                if (worldTransforms[node.index()] == null) {
                    throw fail(BlendDiagnosticCodes.SCENE_005, "/nodes/" + node.index() + "/mesh",
                            "Mesh node is not reachable from the active scene");
                }
                List<MeshPrimitive> mesh = meshes.get(node.meshIndex());
                if (descriptor.profile() == ModelProfile.SKINNED_V1) {
                    if (node.skinIndex() < 0 || skeleton == null || node.skinIndex() >= skeleton.skins().size()) {
                        throw fail(BlendDiagnosticCodes.SKIN_001, "/nodes/" + node.index() + "/skin", "Skinned mesh node must reference a valid skin");
                    }
                    Skin skin = skeleton.skins().get(node.skinIndex());
                    for (int primitiveIndex = 0; primitiveIndex < mesh.size(); primitiveIndex++) {
                        validateSkinPrimitive(mesh.get(primitiveIndex), skin,
                                "/meshes/" + node.meshIndex() + "/primitives/" + primitiveIndex);
                    }
                } else {
                    if (node.skinIndex() >= 0) {
                        throw fail(BlendDiagnosticCodes.SKIN_001, "/nodes/" + node.index() + "/skin", "Rigid mesh node must not reference a skin");
                    }
                    for (MeshPrimitive primitive : mesh) {
                        if (primitive.skinned()) {
                            throw fail(BlendDiagnosticCodes.SKIN_001, "/nodes/" + node.index(),
                                    "Rigid profile must not contain skin vertex attributes");
                        }
                    }
                }
                bindings[node.meshIndex()]++;
                for (int primitiveIndex = 0; primitiveIndex < mesh.size(); primitiveIndex++) {
                    result.add(new ModelPrimitive(node.index(), node.meshIndex(), primitiveIndex, mesh.get(primitiveIndex)));
                }
            }
            for (int meshIndex = 0; meshIndex < bindings.length; meshIndex++) {
                if (bindings[meshIndex] == 0) {
                    throw fail(BlendDiagnosticCodes.SCENE_005, "/meshes/" + meshIndex,
                            "Each strict v1 mesh must be bound to an active scene node");
                }
                if (bindings[meshIndex] > 1) {
                    throw fail(BlendDiagnosticCodes.SCENE_005, "/meshes/" + meshIndex,
                            "Strict v1 does not permit one mesh to bind to multiple scene nodes");
                }
            }
            if (result.isEmpty()) {
                throw fail(BlendDiagnosticCodes.SCENE_005, "/nodes", "Active scene has no renderable primitive binding");
            }
            return List.copyOf(result);
        }

        private void validateAllNodeSkinReferences(List<NodeData> nodes, Skeleton skeleton) {
            for (NodeData node : nodes) {
                if (node.skinIndex() < 0) {
                    continue;
                }
                String pointer = "/nodes/" + node.index() + "/skin";
                if (descriptor.profile() != ModelProfile.SKINNED_V1) {
                    throw fail(BlendDiagnosticCodes.SKIN_001, pointer, "Rigid profile must not contain a node skin reference");
                }
                if (node.meshIndex() < 0) {
                    throw fail(BlendDiagnosticCodes.SKIN_001, pointer, "A strict v1 skin reference requires a mesh node");
                }
                if (skeleton == null || node.skinIndex() >= skeleton.skins().size()) {
                    throw fail(BlendDiagnosticCodes.SKIN_001, pointer, "Node skin reference is outside the decoded skin list");
                }
            }
        }

        private void emitSkinPerformanceWarning(List<NodeData> nodes, Skeleton skeleton) {
            if (skeleton == null) {
                return;
            }
            Set<Integer> relevantSkinIndices = new LinkedHashSet<>();
            for (NodeData node : nodes) {
                if (node.meshIndex() >= 0 && node.skinIndex() >= 0) {
                    relevantSkinIndices.add(node.skinIndex());
                }
            }
            long totalJoints = 0;
            boolean anyLargeSkin = false;
            for (int skinIndex : relevantSkinIndices) {
                int jointCount = skeleton.skins().get(skinIndex).joints().size();
                totalJoints += jointCount;
                anyLargeSkin |= jointCount > SKIN_JOINT_WARNING_THRESHOLD;
            }
            if (anyLargeSkin || totalJoints > SKIN_JOINT_WARNING_THRESHOLD) {
                diagnostics.add(new BlendDiagnostic(DiagnosticSeverity.WARN, BlendDiagnosticCodes.PERF_001, modelKey, glbResourceId,
                        "/skins", "Asset exceeds the non-fatal skin-joint performance-warning threshold", ""));
            }
        }

        private void validateSkinPrimitive(MeshPrimitive primitive, Skin skin, String pointer) {
            if (!primitive.skinned()) {
                throw fail(BlendDiagnosticCodes.SKIN_001, pointer, "Skinned profile primitive requires JOINTS_0 and WEIGHTS_0");
            }
            int[] joints = primitive.joints();
            for (int influence = 0; influence < joints.length; influence++) {
                int joint = joints[influence];
                if (joint >= skin.joints().size()) {
                    throw fail(BlendDiagnosticCodes.SKIN_001, pointer + "/attributes/JOINTS_0",
                            "Vertex joint index is outside the bound skin at vertex " + (influence / 4)
                                    + ", influence " + (influence % 4));
                }
            }
        }

        /**
         * A zero-weight slot is inert padding and may repeat another slot. Two positive-weight
         * slots naming the same palette entry would double-count one joint and are rejected.
         */
        private void validateVertexInfluences(int[] joints, float[] weights, String primitivePointer) {
            for (int vertex = 0; vertex < joints.length / 4; vertex++) {
                int base = vertex * 4;
                for (int first = 0; first < 4; first++) {
                    if (!(weights[base + first] > 0.0f)) {
                        continue;
                    }
                    for (int second = first + 1; second < 4; second++) {
                        if (weights[base + second] > 0.0f && joints[base + first] == joints[base + second]) {
                            throw fail(BlendDiagnosticCodes.SKIN_001,
                                    primitivePointer + "/attributes/JOINTS_0",
                                    "Vertex " + vertex + " repeats joint " + joints[base + first]
                                            + " in positive-weight influences " + first + " and " + second);
                        }
                    }
                }
            }
        }

        /**
         * Returns the closest common ancestor, counting a joint as its own ancestor. The already
         * validated unique-parent forest makes a missing result exactly the disjoint-tree case.
         */
        private int closestCommonAncestor(List<Integer> joints, int[] parents) {
            for (int candidate = joints.getFirst(); candidate >= 0; candidate = parents[candidate]) {
                boolean common = true;
                for (int joint : joints) {
                    if (!isAncestorOrSelf(candidate, joint, parents)) {
                        common = false;
                        break;
                    }
                }
                if (common) {
                    return candidate;
                }
            }
            return -1;
        }

        private boolean isAncestorOrSelf(int ancestor, int node, int[] parents) {
            for (int current = node; current >= 0; current = parents[current]) {
                if (current == ancestor) {
                    return true;
                }
            }
            return false;
        }

        private void validateInverseBindMatrices(float[] inverseBinds, String pointer) {
            for (int matrix = 0; matrix < inverseBinds.length / 16; matrix++) {
                int offset = matrix * 16;
                if (Math.abs(inverseBinds[offset + 3]) > 1.0e-5f
                        || Math.abs(inverseBinds[offset + 7]) > 1.0e-5f
                        || Math.abs(inverseBinds[offset + 11]) > 1.0e-5f
                        || Math.abs(inverseBinds[offset + 15] - 1.0f) > 1.0e-5f) {
                    throw fail(BlendDiagnosticCodes.SKIN_001, pointer,
                            "Inverse-bind matrix " + matrix + " must have affine fourth row [0, 0, 0, 1]");
                }
            }
        }

        private Bounds calculateBounds(List<ModelPrimitive> primitives, Transform[] worldTransforms) {
            Bounds result = null;
            for (ModelPrimitive primitive : primitives) {
                Bounds transformed;
                try {
                    transformed = primitive.geometry().localBounds().transformed(worldTransforms[primitive.nodeIndex()]);
                } catch (IllegalArgumentException exception) {
                    throw fail(BlendDiagnosticCodes.SCENE_005, "/nodes/" + primitive.nodeIndex(),
                            "Scene bounds transform is invalid or non-finite", exception);
                }
                result = result == null ? transformed : result.union(transformed);
            }
            if (result == null) {
                throw fail(BlendDiagnosticCodes.SCENE_005, "/nodes", "No bounds can be calculated without primitives");
            }
            return result;
        }

        private List<AnimationClip> decodeAnimations(List<NodeData> nodes) {
            JsonValue animationValue = root.get("animations");
            if (animationValue == null) {
                return List.of();
            }
            JsonArray animations = array(animationValue, "/animations", BlendDiagnosticCodes.ANIM_007);
            if (animations.size() > limits.maxClips()) {
                throw fail(BlendDiagnosticCodes.LIMIT_001, "/animations", "Animation clip limit exceeded");
            }
            List<AnimationClip> clips = new ArrayList<>();
            Set<String> names = new HashSet<>();
            long samplerInputSamples = 0;
            long channelSamples = 0;
            for (int animationIndex = 0; animationIndex < animations.size(); animationIndex++) {
                String pointer = "/animations/" + animationIndex;
                JsonObject animation = object(animations.get(animationIndex), pointer, BlendDiagnosticCodes.ANIM_007);
                SamplerDecodeResult samplerResult = decodeSamplers(animation, pointer, samplerInputSamples);
                List<SamplerData> samplers = samplerResult.samplers();
                samplerInputSamples = samplerResult.totalInputSamples();
                JsonArray channels = array(required(animation, "channels", pointer + "/channels", BlendDiagnosticCodes.ANIM_007), pointer + "/channels",
                        BlendDiagnosticCodes.ANIM_007);
                if (channels.size() == 0) {
                    throw fail(BlendDiagnosticCodes.ANIM_007, pointer + "/channels", "Animation clip must have channels");
                }
                List<AnimationChannel> decodedChannels = new ArrayList<>();
                Set<AnimationTarget> targets = new HashSet<>();
                for (int channelIndex = 0; channelIndex < channels.size(); channelIndex++) {
                    String channelPointer = pointer + "/channels/" + channelIndex;
                    JsonObject channel = object(channels.get(channelIndex), channelPointer, BlendDiagnosticCodes.ANIM_007);
                    int samplerIndex = integer(required(channel, "sampler", channelPointer + "/sampler", BlendDiagnosticCodes.ANIM_007),
                            channelPointer + "/sampler", BlendDiagnosticCodes.ANIM_007);
                    if (samplerIndex < 0 || samplerIndex >= samplers.size()) {
                        throw fail(BlendDiagnosticCodes.ANIM_007, channelPointer + "/sampler", "Animation sampler reference is invalid");
                    }
                    JsonObject target = object(required(channel, "target", channelPointer + "/target", BlendDiagnosticCodes.ANIM_007),
                            channelPointer + "/target", BlendDiagnosticCodes.ANIM_007);
                    int targetNode = integer(required(target, "node", channelPointer + "/target/node", BlendDiagnosticCodes.ANIM_007),
                            channelPointer + "/target/node", BlendDiagnosticCodes.ANIM_007);
                    if (targetNode < 0 || targetNode >= nodes.size()) {
                        throw fail(BlendDiagnosticCodes.ANIM_007, channelPointer + "/target/node", "Animation target node is invalid");
                    }
                    AnimationPath path;
                    try {
                        path = AnimationPath.fromSerializedName(string(required(target, "path", channelPointer + "/target/path", BlendDiagnosticCodes.ANIM_007),
                                channelPointer + "/target/path", BlendDiagnosticCodes.ANIM_007));
                    } catch (IllegalArgumentException exception) {
                        throw fail(BlendDiagnosticCodes.ANIM_007, channelPointer + "/target/path", "Animation target path is unsupported", exception);
                    }
                    if (nodes.get(targetNode).matrixDeclared()) {
                        throw fail(BlendDiagnosticCodes.ANIM_007, channelPointer + "/target/node",
                                "Animation target node must not declare matrix");
                    }
                    if (!targets.add(new AnimationTarget(targetNode, path))) {
                        throw fail(BlendDiagnosticCodes.ANIM_007, channelPointer + "/target",
                                "Animation channels must not target the same node and path");
                    }
                    SamplerData sampler = samplers.get(samplerIndex);
                    var outputInfo = accessors.info(sampler.outputAccessor());
                    String expectedType = path == AnimationPath.ROTATION ? "VEC4" : "VEC3";
                    if (!expectedType.equals(outputInfo.type()) || outputInfo.componentType() != 5126
                            || outputInfo.count() != sampler.times().length) {
                        throw fail(BlendDiagnosticCodes.ANIM_007, channelPointer,
                                "Animation sampler output accessor does not match its target path and input count");
                    }
                    channelSamples = addBounded(channelSamples, sampler.times().length, limits.maxKeyframeSamples(), channelPointer,
                            "Animation keyframe sample limit exceeded");
                    float[] values = accessors.readFloatElements(sampler.outputAccessor(), path == AnimationPath.ROTATION ? "VEC4" : "VEC3");
                    if (values.length != sampler.times().length * path.components()) {
                        throw fail(BlendDiagnosticCodes.ANIM_007, channelPointer, "Animation sampler output count does not match input times");
                    }
                    try {
                        decodedChannels.add(new AnimationChannel(targetNode, path, sampler.interpolation(), sampler.times(), values));
                    } catch (IllegalArgumentException exception) {
                        throw fail(BlendDiagnosticCodes.ANIM_007, channelPointer, "Animation channel is invalid", exception);
                    }
                }
                String name = animation.containsKey("name")
                        ? string(animation.get("name"), pointer + "/name", BlendDiagnosticCodes.ANIM_007)
                        : "animation-" + animationIndex;
                if (name.isBlank() || !names.add(name)) {
                    throw fail(BlendDiagnosticCodes.ANIM_007, pointer + "/name", "Animation names must be unique and non-blank");
                }
                try {
                    clips.add(new AnimationClip(name, decodedChannels));
                } catch (IllegalArgumentException exception) {
                    throw fail(BlendDiagnosticCodes.ANIM_007, pointer, "Animation clip is invalid", exception);
                }
            }
            return List.copyOf(clips);
        }

        private SamplerDecodeResult decodeSamplers(JsonObject animation, String pointer, long startingInputSamples) {
            JsonArray samplers = array(required(animation, "samplers", pointer + "/samplers", BlendDiagnosticCodes.ANIM_007), pointer + "/samplers",
                    BlendDiagnosticCodes.ANIM_007);
            if (samplers.size() == 0) {
                throw fail(BlendDiagnosticCodes.ANIM_007, pointer + "/samplers", "Animation clip must have samplers");
            }
            List<SamplerData> result = new ArrayList<>();
            long inputSamples = startingInputSamples;
            for (int samplerIndex = 0; samplerIndex < samplers.size(); samplerIndex++) {
                String samplerPointer = pointer + "/samplers/" + samplerIndex;
                JsonObject sampler = object(samplers.get(samplerIndex), samplerPointer, BlendDiagnosticCodes.ANIM_007);
                int inputAccessor = integer(required(sampler, "input", samplerPointer + "/input", BlendDiagnosticCodes.ANIM_007),
                        samplerPointer + "/input", BlendDiagnosticCodes.ANIM_007);
                int outputAccessor = integer(required(sampler, "output", samplerPointer + "/output", BlendDiagnosticCodes.ANIM_007),
                        samplerPointer + "/output", BlendDiagnosticCodes.ANIM_007);
                String interpolationText = sampler.containsKey("interpolation")
                        ? string(sampler.get("interpolation"), samplerPointer + "/interpolation", BlendDiagnosticCodes.ANIM_007)
                        : "LINEAR";
                Interpolation interpolation;
                try {
                    interpolation = Interpolation.fromSerializedName(interpolationText);
                } catch (IllegalArgumentException exception) {
                    throw fail(BlendDiagnosticCodes.ANIM_007, samplerPointer + "/interpolation",
                            "CUBICSPLINE and other interpolation modes are not supported", exception);
                }
                var inputInfo = accessors.requireMinMax(inputAccessor);
                if (!"SCALAR".equals(inputInfo.type()) || inputInfo.componentType() != 5126 || inputInfo.count() == 0) {
                    throw fail(BlendDiagnosticCodes.ANIM_007, samplerPointer + "/input",
                            "Animation sampler input must be a non-empty FLOAT SCALAR accessor");
                }
                inputSamples = addBounded(inputSamples, inputInfo.count(), limits.maxKeyframeSamples(), samplerPointer + "/input",
                        "Animation sampler input sample limit exceeded");
                float[] times = accessors.readFloatElements(inputAccessor, "SCALAR");
                if (times.length == 0) {
                    throw fail(BlendDiagnosticCodes.ANIM_007, samplerPointer + "/input", "Animation sampler must contain times");
                }
                if (times[0] < 0.0f) {
                    throw fail(BlendDiagnosticCodes.ANIM_007, samplerPointer + "/input", "Animation times must be non-negative");
                }
                for (int index = 1; index < times.length; index++) {
                    if (!(times[index] > times[index - 1])) {
                        throw fail(BlendDiagnosticCodes.ANIM_006, samplerPointer + "/input", "Animation times must be strictly increasing");
                    }
                }
                if (times[times.length - 1] > limits.maxClipDurationSeconds()) {
                    throw fail(BlendDiagnosticCodes.LIMIT_001, samplerPointer + "/input", "Animation duration limit exceeded");
                }
                result.add(new SamplerData(times, outputAccessor, interpolation));
            }
            return new SamplerDecodeResult(List.copyOf(result), inputSamples);
        }

        private void validateDescriptorClipReferences(List<AnimationClip> clips) {
            if (descriptor.animation() == null) {
                return;
            }
            Map<String, AnimationClip> clipsByName = new HashMap<>();
            for (AnimationClip clip : clips) {
                clipsByName.put(clip.name(), clip);
            }
            for (Map.Entry<BlendResourceId, com.liy.blendlib.core.descriptor.AnimationStateDefinition> entry
                    : descriptor.animation().states().entrySet()) {
                var state = entry.getValue();
                AnimationClip clip = clipsByName.get(state.clip());
                if (clip == null) {
                    throw LoaderFailure.error(BlendDiagnosticCodes.DESC_002, modelKey, descriptor.descriptorId(), "/animation/states",
                            "Descriptor animation state references a missing GLB clip");
                }
                if (state.nextState() != null && !descriptor.animation().states().containsKey(state.nextState())) {
                    throw LoaderFailure.error(BlendDiagnosticCodes.DESC_002, modelKey, descriptor.descriptorId(), "/animation/states",
                            "Descriptor animation state references an undeclared next state");
                }
                String statePointer = "/animation/states/" + escapeJsonPointer(entry.getKey().value());
                for (int eventIndex = 0; eventIndex < state.events().size(); eventIndex++) {
                    var event = state.events().get(eventIndex);
                    if (event.timeSeconds() > clip.durationSeconds()) {
                        throw LoaderFailure.error(BlendDiagnosticCodes.DESC_002, modelKey, descriptor.descriptorId(),
                                statePointer + "/events/" + eventIndex + "/time_seconds",
                                "Descriptor animation event time exceeds its referenced decoded clip duration");
                    }
                }
            }
        }

        /**
         * Applies ADR-015 after strict GLB decoding has established both the selected default
         * scene and the descriptor-to-clip mapping. The default-scene roots and their reachable
         * descendants are the only canonical hierarchy; a skinned render path must never promote
         * an otherwise detached joint or skeleton root implicitly at render time.
         */
        private void validateCanonicalActiveHierarchy(
                List<NodeData> nodes,
                SceneData scene,
                Skeleton skeleton,
                List<ModelPrimitive> primitives,
                List<AnimationClip> clips) {
            if (descriptor.profile() != ModelProfile.SKINNED_V1 || skeleton == null) {
                return;
            }

            boolean[] activeNodes = new boolean[nodes.size()];
            for (int nodeIndex = 0; nodeIndex < activeNodes.length; nodeIndex++) {
                activeNodes[nodeIndex] = scene.worldTransforms()[nodeIndex] != null;
            }

            Set<Integer> requiredSkinIndices = new LinkedHashSet<>();
            for (ModelPrimitive primitive : primitives) {
                int skinIndex = nodes.get(primitive.nodeIndex()).skinIndex();
                if (skinIndex >= 0) {
                    requiredSkinIndices.add(skinIndex);
                }
            }
            for (int skinIndex : requiredSkinIndices) {
                Skin skin = skeleton.skins().get(skinIndex);
                if (skin.skeletonRoot() >= 0 && !activeNodes[skin.skeletonRoot()]) {
                    throw fail(BlendDiagnosticCodes.SCENE_005, "/skins/" + skinIndex + "/skeleton",
                            "Skin required by an active primitive has a skeleton root outside the canonical default-scene hierarchy");
                }
                for (int jointSlot = 0; jointSlot < skin.joints().size(); jointSlot++) {
                    int jointNode = skin.joints().get(jointSlot);
                    if (!activeNodes[jointNode]) {
                        throw fail(BlendDiagnosticCodes.SCENE_005, "/skins/" + skinIndex + "/joints/" + jointSlot,
                                "Skin required by an active primitive has a joint outside the canonical default-scene hierarchy");
                    }
                }
            }

            Set<Integer> allSkinJointNodes = new HashSet<>();
            for (Skin skin : skeleton.skins()) {
                allSkinJointNodes.addAll(skin.joints());
            }
            for (AnimationClip clip : clips) {
                for (AnimationChannel channel : clip.channels()) {
                    if (allSkinJointNodes.contains(channel.targetNode()) && !activeNodes[channel.targetNode()]) {
                        throw fail(BlendDiagnosticCodes.SCENE_005, "/animations",
                                "Animation targets a skin joint outside the canonical default-scene hierarchy");
                    }
                }
            }
        }

        private static String escapeJsonPointer(String segment) {
            return segment.replace("~", "~0").replace("/", "~1");
        }

        private SocketTable decodeSockets(List<NodeData> nodes, List<Integer> roots) {
            if (descriptor.sockets().isEmpty()) {
                return new SocketTable(Map.of());
            }
            String[] nodePaths = new String[nodes.size()];
            Map<String, Integer> uniquePaths = new HashMap<>();
            Set<String> ambiguousPaths = new HashSet<>();
            ArrayDeque<Integer> pending = new ArrayDeque<>();
            for (int rootNode : roots) {
                nodePaths[rootNode] = nodes.get(rootNode).name();
                pending.add(rootNode);
            }
            while (!pending.isEmpty()) {
                int node = pending.removeFirst();
                String path = nodePaths[node];
                Integer prior = uniquePaths.putIfAbsent(path, node);
                if (prior != null && prior != node) {
                    ambiguousPaths.add(path);
                }
                for (int child : nodes.get(node).children()) {
                    nodePaths[child] = path + "/" + nodes.get(child).name();
                    pending.addLast(child);
                }
            }
            Map<BlendResourceId, SocketTable.Socket> sockets = new LinkedHashMap<>();
            for (Map.Entry<BlendResourceId, String> requested : descriptor.sockets().entrySet()) {
                Integer node = uniquePaths.get(requested.getValue());
                if (node == null || ambiguousPaths.contains(requested.getValue())) {
                    throw fail(BlendDiagnosticCodes.SCENE_005, "/sockets", "Descriptor socket path does not resolve to one unique node");
                }
                sockets.put(requested.getKey(), new SocketTable.Socket(node, requested.getValue()));
            }
            return new SocketTable(sockets);
        }

        private int accessorIndex(JsonObject attributes, String name, String pointer) {
            return integer(required(attributes, name, pointer, BlendDiagnosticCodes.GLB_015), pointer, BlendDiagnosticCodes.GLB_015);
        }

        private Vec3 vec3(JsonValue value, String pointer) {
            float[] values = floatArray(array(value, pointer, BlendDiagnosticCodes.SCENE_005), 3, pointer, BlendDiagnosticCodes.SCENE_005);
            return new Vec3(values[0], values[1], values[2]);
        }

        private Quaternion quaternion(JsonValue value, String pointer) {
            float[] values = floatArray(array(value, pointer, BlendDiagnosticCodes.SCENE_005), 4, pointer, BlendDiagnosticCodes.SCENE_005);
            return new Quaternion(values[0], values[1], values[2], values[3]).normalized();
        }

        private float[] floatArray(JsonArray array, int expectedSize, String pointer, String code) {
            if (array.size() != expectedSize) {
                throw fail(code, pointer, "JSON numeric array has an invalid element count");
            }
            float[] result = new float[expectedSize];
            for (int index = 0; index < expectedSize; index++) {
                result[index] = finiteFloat(array.get(index), pointer + "/" + index, code);
            }
            return result;
        }

        private List<Integer> integerList(JsonArray array, String pointer, String code) {
            List<Integer> result = new ArrayList<>();
            for (int index = 0; index < array.size(); index++) {
                result.add(integer(array.get(index), pointer + "/" + index, code));
            }
            return List.copyOf(result);
        }

        private long addBounded(long current, long addition, long limit, String pointer, String message) {
            long result;
            try {
                result = Math.addExact(current, addition);
            } catch (ArithmeticException exception) {
                throw fail(BlendDiagnosticCodes.LIMIT_001, pointer, message, exception);
            }
            if (result > limit) {
                throw fail(BlendDiagnosticCodes.LIMIT_001, pointer, message);
            }
            return result;
        }

        private JsonValue required(JsonObject object, String key, String pointer, String code) {
            JsonValue value = object.get(key);
            if (value == null) {
                throw fail(code, pointer, "Required GLB field is missing");
            }
            return value;
        }

        private JsonObject object(JsonValue value, String pointer, String code) {
            if (value instanceof JsonObject object) {
                return object;
            }
            throw fail(code, pointer, "Expected a JSON object");
        }

        private JsonArray array(JsonValue value, String pointer, String code) {
            if (value instanceof JsonArray array) {
                return array;
            }
            throw fail(code, pointer, "Expected a JSON array");
        }

        private String string(JsonValue value, String pointer, String code) {
            if (value instanceof JsonString string) {
                return string.value();
            }
            throw fail(code, pointer, "Expected a JSON string");
        }

        private int integer(JsonValue value, String pointer, String code) {
            if (!(value instanceof JsonNumber number)) {
                throw fail(code, pointer, "Expected an integer JSON number");
            }
            try {
                return number.asIntExact();
            } catch (IllegalArgumentException exception) {
                throw fail(code, pointer, "Expected a 32-bit integer JSON number", exception);
            }
        }

        private float finiteFloat(JsonValue value, String pointer, String code) {
            if (!(value instanceof JsonNumber number)) {
                throw fail(code, pointer, "Expected a JSON number");
            }
            try {
                double parsed = number.asDouble();
                float result = (float) parsed;
                if (!Double.isFinite(parsed) || !Float.isFinite(result)) {
                    throw new IllegalArgumentException("non-finite");
                }
                return result;
            } catch (IllegalArgumentException exception) {
                throw fail(code, pointer, "Numeric value must be finite", exception);
            }
        }

        private boolean isEmptyObject(JsonValue value, String pointer) {
            return object(value, pointer, BlendDiagnosticCodes.GLB_015).size() == 0;
        }

        private BlendAssetLoadException fail(String code, String pointer, String message) {
            return LoaderFailure.error(code, modelKey, glbResourceId, pointer, message);
        }

        private BlendAssetLoadException fail(String code, String pointer, String message, Throwable cause) {
            return LoaderFailure.error(code, modelKey, glbResourceId, pointer, message, cause);
        }

        private record NodeData(
                int index,
                String name,
                Transform localTransform,
                List<Integer> children,
                int meshIndex,
                int skinIndex,
                boolean cameraOrLightIgnored,
                boolean matrixDeclared) {
        }

        private record SceneData(List<Integer> rootNodes, Transform[] worldTransforms, int[] parents) {
        }

        private record SamplerData(float[] times, int outputAccessor, Interpolation interpolation) {
        }

        private record SamplerDecodeResult(List<SamplerData> samplers, long totalInputSamples) {
        }

        private record AnimationTarget(int nodeIndex, AnimationPath path) {
        }

        private static final class TraversalFrame {
            private final int node;
            private int nextChild;

            TraversalFrame(int node, int nextChild) {
                this.node = node;
                this.nextChild = nextChild;
            }

            int node() {
                return node;
            }

            int nextChild() {
                return nextChild;
            }

            void advance() {
                nextChild++;
            }
        }

        private record NodeDepth(int node, int depth) {
        }
    }
}
