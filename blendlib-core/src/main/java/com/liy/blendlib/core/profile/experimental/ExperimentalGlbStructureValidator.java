package com.liy.blendlib.core.profile.experimental;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.glb.AccessorInfo;
import com.liy.blendlib.core.glb.GlbAccessorReader;
import com.liy.blendlib.core.glb.GlbDocument;
import com.liy.blendlib.core.json.JsonArray;
import com.liy.blendlib.core.json.JsonNumber;
import com.liy.blendlib.core.json.JsonObject;
import com.liy.blendlib.core.json.JsonString;
import com.liy.blendlib.core.json.JsonValue;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Strict structural envelope for X9 validation-only GLB candidates.
 *
 * <p>This class composes the existing {@link GlbAccessorReader} for all byte
 * bounds and typed accessor layout checks. It adds only v2 candidate structure,
 * reference, hierarchy, skin, and extension validation; it does not decode a
 * runtime asset or duplicate the v1 loader's model/material/animation output.</p>
 */
final class ExperimentalGlbStructureValidator {
    private static final int FLOAT = 5126;
    private static final int MAX_STRUCTURAL_ENTRIES = 16_384;
    private static final Set<String> DISABLED_EXTENSIONS = Set.of(
            "KHR_draco_mesh_compression", "EXT_meshopt_compression", "KHR_texture_basisu");
    private static final Set<String> ROOT_FIELDS = Set.of(
            "asset", "buffers", "bufferViews", "accessors", "materials", "meshes", "nodes", "skins",
            "scenes", "scene", "animations", "extensionsUsed", "extensionsRequired", "extensions");
    private static final Set<String> ASSET_FIELDS = Set.of("version", "minVersion", "generator", "copyright", "extensions");
    private static final Set<String> BUFFER_FIELDS = Set.of("byteLength", "name", "extensions");
    private static final Set<String> BUFFER_VIEW_FIELDS =
            Set.of("buffer", "byteOffset", "byteLength", "byteStride", "target", "name", "extensions");
    private static final Set<String> ACCESSOR_FIELDS =
            Set.of("bufferView", "byteOffset", "componentType", "normalized", "count", "type", "min", "max", "name", "extensions");
    private static final Set<String> NODE_FIELDS =
            Set.of("name", "children", "mesh", "skin", "matrix", "translation", "rotation", "scale", "weights", "extensions");
    private static final Set<String> SCENE_FIELDS = Set.of("name", "nodes", "extensions");
    private static final Set<String> SKIN_FIELDS = Set.of("name", "inverseBindMatrices", "skeleton", "joints", "extensions");

    private final BlendResourceId modelKey;
    private final BlendResourceId resourceId;
    private final GlbDocument document;
    private final JsonObject root;
    private final ExperimentalProfileLimits limits;
    private final GlbAccessorReader accessors;

    ExperimentalGlbStructureValidator(
            BlendResourceId modelKey,
            BlendResourceId resourceId,
            GlbDocument document,
            ExperimentalProfileLimits limits) {
        this.modelKey = Objects.requireNonNull(modelKey, "modelKey");
        this.resourceId = Objects.requireNonNull(resourceId, "resourceId");
        this.document = Objects.requireNonNull(document, "document");
        this.root = document.json();
        this.limits = Objects.requireNonNull(limits, "limits");
        this.accessors = new GlbAccessorReader(modelKey, resourceId, document, limits.baseGlbLimits());
    }

    Result validate() {
        rejectUnknown(root, ROOT_FIELDS, "");
        rejectDeclaredAndPayloadExtensions();
        validateAssetMetadata();
        validateBuffersAndAccessors();

        JsonArray meshes = array(required(root, "meshes", "/meshes"), "/meshes");
        if (meshes.size() == 0 || meshes.size() > limits.baseGlbLimits().maxNodes()) {
            throw error(meshes.size() == 0 ? "BLENDLIB-X9-GLB-015" : "BLENDLIB-X9-LIMIT-001",
                    "/meshes", "X9 mesh count is outside the structural bound");
        }
        List<NodeInfo> nodes = validateNodes(meshes.size());
        HierarchyInfo hierarchy = validateScenesAndHierarchy(nodes);
        List<SkinInfo> skins = validateSkins(nodes, hierarchy);
        validateNodeSkinAndMeshBindings(nodes, skins, hierarchy.activeNodes(), meshes.size());
        return new Result(accessors, List.copyOf(nodes), List.copyOf(skins), Set.copyOf(hierarchy.activeNodes()));
    }

    private void validateAssetMetadata() {
        JsonObject asset = object(required(root, "asset", "/asset"), "/asset");
        rejectUnknown(asset, ASSET_FIELDS, "/asset");
        String version = string(required(asset, "version", "/asset/version"), "/asset/version");
        if (!"2.0".equals(version)) {
            throw error("BLENDLIB-X9-GLB-002", "/asset/version", "X9 requires exact glTF asset version 2.0");
        }
        if (asset.containsKey("minVersion")
                && !"2.0".equals(string(asset.get("minVersion"), "/asset/minVersion"))) {
            throw error("BLENDLIB-X9-GLB-002", "/asset/minVersion",
                    "X9 does not accept a glTF minimum version above 2.0");
        }
        for (String field : List.of("generator", "copyright")) {
            if (asset.containsKey(field)) {
                string(asset.get(field), "/asset/" + field);
            }
        }
    }

    private void rejectDeclaredAndPayloadExtensions() {
        for (String field : List.of("extensionsRequired", "extensionsUsed")) {
            JsonValue value = root.get(field);
            if (value == null) {
                continue;
            }
            JsonArray declared = array(value, "/" + field);
            Set<String> unique = new HashSet<>();
            for (int index = 0; index < declared.size(); index++) {
                String extension = string(declared.get(index), "/" + field + "/" + index);
                if (!unique.add(extension)) {
                    throw error("BLENDLIB-X9-GLB-015", "/" + field + "/" + index,
                            "GLB extension declarations must be unique");
                }
                if (DISABLED_EXTENSIONS.contains(extension) || "extensionsRequired".equals(field)) {
                    throw error("BLENDLIB-EXT-001", "/" + field + "/" + index,
                            "Required or disabled GLB extensions are unavailable in X9");
                }
                throw error("BLENDLIB-X9-EXT-003", "/" + field + "/" + index,
                        "GLB extensionsUsed has no semantic-equivalent X9 fallback");
            }
        }
        scanExtensionPayloads(root, "");
    }

    private void scanExtensionPayloads(JsonValue value, String pointer) {
        if (value instanceof JsonObject object) {
            for (var entry : object.values().entrySet()) {
                String childPointer = pointer + "/" + escape(entry.getKey());
                if ("extensions".equals(entry.getKey())) {
                    JsonObject extensions = object(entry.getValue(), childPointer);
                    for (String extension : extensions.values().keySet()) {
                        String location = childPointer + "/" + escape(extension);
                        if (DISABLED_EXTENSIONS.contains(extension)) {
                            throw error("BLENDLIB-EXT-001", location,
                                    "Draco, Meshopt, and KTX2 extension payloads remain disabled");
                        }
                        throw error("BLENDLIB-X9-EXT-003", location,
                                "GLB extension payload has no semantic-equivalent X9 fallback");
                    }
                    continue;
                }
                scanExtensionPayloads(entry.getValue(), childPointer);
            }
        } else if (value instanceof JsonArray array) {
            for (int index = 0; index < array.size(); index++) {
                scanExtensionPayloads(array.get(index), pointer + "/" + index);
            }
        }
    }

    private void validateBuffersAndAccessors() {
        JsonArray buffers = array(required(root, "buffers", "/buffers"), "/buffers");
        if (buffers.size() != 1) {
            throw error("BLENDLIB-X9-GLB-002", "/buffers", "X9 requires exactly one embedded GLB buffer");
        }
        JsonObject buffer = object(buffers.get(0), "/buffers/0");
        rejectUnknown(buffer, BUFFER_FIELDS, "/buffers/0");
        validateOptionalString(buffer, "name", "/buffers/0/name");
        int declaredLength = nonNegativeInteger(required(buffer, "byteLength", "/buffers/0/byteLength"),
                "/buffers/0/byteLength");
        if (declaredLength > document.binarySize()) {
            throw error("BLENDLIB-GLB-014", "/buffers/0/byteLength",
                    "Declared buffer length exceeds the embedded BIN chunk");
        }

        JsonArray views = array(required(root, "bufferViews", "/bufferViews"), "/bufferViews");
        JsonArray accessorArray = array(required(root, "accessors", "/accessors"), "/accessors");
        if (views.size() == 0 || accessorArray.size() == 0
                || views.size() > MAX_STRUCTURAL_ENTRIES || accessorArray.size() > MAX_STRUCTURAL_ENTRIES) {
            throw error(views.size() > MAX_STRUCTURAL_ENTRIES || accessorArray.size() > MAX_STRUCTURAL_ENTRIES
                            ? "BLENDLIB-X9-LIMIT-001" : "BLENDLIB-X9-GLB-002",
                    "/accessors", "X9 buffer-view/accessor count is outside bounds");
        }
        for (int index = 0; index < views.size(); index++) {
            JsonObject view = object(views.get(index), "/bufferViews/" + index);
            rejectUnknown(view, BUFFER_VIEW_FIELDS, "/bufferViews/" + index);
            validateOptionalString(view, "name", "/bufferViews/" + index + "/name");
            if (view.containsKey("target")) {
                int target = nonNegativeInteger(view.get("target"), "/bufferViews/" + index + "/target");
                if (target != 34_962 && target != 34_963) {
                    throw error("BLENDLIB-X9-GLB-015", "/bufferViews/" + index + "/target",
                            "Buffer-view target must be ARRAY_BUFFER or ELEMENT_ARRAY_BUFFER");
                }
            }
        }
        Set<Integer> referencedViews = new HashSet<>();
        for (int index = 0; index < accessorArray.size(); index++) {
            String pointer = "/accessors/" + index;
            JsonObject accessor = object(accessorArray.get(index), pointer);
            rejectUnknown(accessor, ACCESSOR_FIELDS, pointer);
            validateOptionalString(accessor, "name", pointer + "/name");
            int view = nonNegativeInteger(required(accessor, "bufferView", pointer + "/bufferView"), pointer + "/bufferView");
            if (view >= views.size()) {
                throw error("BLENDLIB-GLB-014", pointer + "/bufferView", "Accessor buffer-view reference is invalid");
            }
            referencedViews.add(view);
            AccessorInfo info = accessors.info(index);
            if (info.componentType() == FLOAT && info.normalized()) {
                throw error("BLENDLIB-X9-GLB-015", pointer + "/normalized",
                        "FLOAT accessors must not declare normalized=true");
            }
            validateAccessorBoundsMetadata(accessor, info, pointer);
        }
        if (referencedViews.size() != views.size()) {
            throw error("BLENDLIB-X9-GLB-015", "/bufferViews",
                    "Every X9 buffer view must be referenced by a validated accessor");
        }
    }

    private void validateAccessorBoundsMetadata(JsonObject accessor, AccessorInfo info, String pointer) {
        if (accessor.containsKey("min") != accessor.containsKey("max")) {
            throw error("BLENDLIB-X9-GLB-015", pointer,
                    "Accessor min and max metadata must be declared together");
        }
        double[] minimum = null;
        double[] maximum = null;
        for (String field : List.of("min", "max")) {
            if (!accessor.containsKey(field)) {
                continue;
            }
            JsonArray values = array(accessor.get(field), pointer + "/" + field);
            if (values.size() != info.componentCount()) {
                throw error("BLENDLIB-X9-GLB-015", pointer + "/" + field,
                        "Accessor min/max metadata must match the accessor component count");
            }
            double[] decoded = new double[values.size()];
            for (int index = 0; index < values.size(); index++) {
                decoded[index] = finite(number(values.get(index), pointer + "/" + field + "/" + index),
                        pointer + "/" + field + "/" + index);
            }
            if ("min".equals(field)) {
                minimum = decoded;
            } else {
                maximum = decoded;
            }
        }
        if (minimum != null) {
            for (int index = 0; index < minimum.length; index++) {
                if (minimum[index] > maximum[index]) {
                    throw error("BLENDLIB-X9-GLB-015", pointer + "/min/" + index,
                            "Accessor minimum must not exceed its maximum");
                }
            }
        }
    }

    private List<NodeInfo> validateNodes(int meshCount) {
        JsonArray nodeArray = array(required(root, "nodes", "/nodes"), "/nodes");
        if (nodeArray.size() == 0 || nodeArray.size() > limits.baseGlbLimits().maxNodes()) {
            throw error(nodeArray.size() == 0 ? "BLENDLIB-X9-GLB-015" : "BLENDLIB-X9-LIMIT-001",
                    "/nodes", "X9 node count is outside bounds");
        }
        List<NodeInfo> nodes = new ArrayList<>(nodeArray.size());
        for (int index = 0; index < nodeArray.size(); index++) {
            String pointer = "/nodes/" + index;
            JsonObject node = object(nodeArray.get(index), pointer);
            rejectUnknown(node, NODE_FIELDS, pointer);
            validateOptionalString(node, "name", pointer + "/name");
            int mesh = optionalIndex(node, "mesh", -1, pointer + "/mesh");
            if (mesh >= meshCount) {
                throw error("BLENDLIB-X9-GLB-015", pointer + "/mesh", "Node mesh reference is invalid");
            }
            int skin = optionalIndex(node, "skin", -1, pointer + "/skin");
            List<Integer> children = node.containsKey("children")
                    ? boundedIntegerList(array(node.get("children"), pointer + "/children"),
                            limits.baseGlbLimits().maxNodes(), pointer + "/children",
                            "Node child count exceeds the X9 bound")
                    : List.of();
            if (new HashSet<>(children).size() != children.size()) {
                throw error("BLENDLIB-X9-GLB-015", pointer + "/children", "Node children must be unique");
            }
            validateNodeTransform(node, pointer);
            List<Double> weights = node.containsKey("weights")
                    ? boundedFiniteList(array(node.get("weights"), pointer + "/weights"),
                            limits.maxMorphTargetsPerPrimitive(), pointer + "/weights",
                            "Node morph-weight count exceeds the X9 bound")
                    : List.of();
            if (!weights.isEmpty() && mesh < 0) {
                throw error("BLENDLIB-X9-GLB-015", pointer + "/weights",
                        "Node morph weights require a bound mesh");
            }
            nodes.add(new NodeInfo(index, mesh, skin, children, node.containsKey("matrix"), weights));
        }
        for (NodeInfo node : nodes) {
            for (int child : node.children()) {
                if (child < 0 || child >= nodes.size()) {
                    throw error("BLENDLIB-X9-GLB-015", "/nodes/" + node.index() + "/children",
                            "Node child reference is invalid");
                }
            }
        }
        return nodes;
    }

    private void validateNodeTransform(JsonObject node, String pointer) {
        boolean matrix = node.containsKey("matrix");
        boolean trs = node.containsKey("translation") || node.containsKey("rotation") || node.containsKey("scale");
        if (matrix && trs) {
            throw error("BLENDLIB-X9-GLB-015", pointer, "Node matrix cannot be combined with TRS");
        }
        if (matrix) {
            finiteArray(array(node.get("matrix"), pointer + "/matrix"), 16, pointer + "/matrix");
        }
        if (node.containsKey("translation")) {
            finiteArray(array(node.get("translation"), pointer + "/translation"), 3, pointer + "/translation");
        }
        if (node.containsKey("scale")) {
            List<Double> scale = finiteArray(array(node.get("scale"), pointer + "/scale"), 3, pointer + "/scale");
            if (scale.stream().anyMatch(value -> value == 0.0)) {
                throw error("BLENDLIB-X9-GLB-015", pointer + "/scale", "Node scale must be non-zero");
            }
        }
        if (node.containsKey("rotation")) {
            List<Double> rotation = finiteArray(array(node.get("rotation"), pointer + "/rotation"), 4, pointer + "/rotation");
            double lengthSquared = rotation.stream().mapToDouble(value -> value * value).sum();
            if (!Double.isFinite(lengthSquared) || Math.abs(lengthSquared - 1.0) > 1.0e-4) {
                throw error("BLENDLIB-X9-GLB-015", pointer + "/rotation",
                        "Node rotation quaternion must be normalized");
            }
        }
    }

    private HierarchyInfo validateScenesAndHierarchy(List<NodeInfo> nodes) {
        int[] parents = new int[nodes.size()];
        Arrays.fill(parents, -1);
        for (NodeInfo node : nodes) {
            for (int child : node.children()) {
                if (parents[child] != -1) {
                    throw error("BLENDLIB-X9-GLB-015", "/nodes/" + node.index() + "/children",
                            "An X9 node may have only one parent");
                }
                parents[child] = node.index();
            }
        }
        validateAcyclicAndDepthBounded(nodes, parents);
        HierarchyOrder hierarchyOrder = indexHierarchy(nodes, parents);

        JsonArray scenes = array(required(root, "scenes", "/scenes"), "/scenes");
        if (scenes.size() == 0 || scenes.size() > nodes.size()) {
            throw error(scenes.size() == 0 ? "BLENDLIB-X9-GLB-015" : "BLENDLIB-X9-LIMIT-001",
                    "/scenes", "X9 scene count is outside bounds");
        }
        int defaultScene = nonNegativeInteger(required(root, "scene", "/scene"), "/scene");
        if (defaultScene >= scenes.size()) {
            throw error("BLENDLIB-X9-GLB-015", "/scene", "Default scene reference is invalid");
        }
        List<List<Integer>> sceneRoots = new ArrayList<>(scenes.size());
        for (int index = 0; index < scenes.size(); index++) {
            String pointer = "/scenes/" + index;
            JsonObject scene = object(scenes.get(index), pointer);
            rejectUnknown(scene, SCENE_FIELDS, pointer);
            validateOptionalString(scene, "name", pointer + "/name");
            List<Integer> roots = boundedIntegerList(
                    array(required(scene, "nodes", pointer + "/nodes"), pointer + "/nodes"),
                    nodes.size(), pointer + "/nodes", "Scene root count exceeds the X9 bound");
            if (roots.isEmpty() || new HashSet<>(roots).size() != roots.size()) {
                throw error("BLENDLIB-X9-GLB-015", pointer + "/nodes", "Scene roots must be non-empty and unique");
            }
            for (int rootNode : roots) {
                if (rootNode < 0 || rootNode >= nodes.size() || parents[rootNode] != -1) {
                    throw error("BLENDLIB-X9-GLB-015", pointer + "/nodes",
                            "Scene root must reference a structural root node");
                }
            }
            sceneRoots.add(roots);
        }

        Set<Integer> active = new LinkedHashSet<>();
        ArrayDeque<Integer> pending = new ArrayDeque<>(sceneRoots.get(defaultScene));
        while (!pending.isEmpty()) {
            int node = pending.removeFirst();
            if (!active.add(node)) {
                throw error("BLENDLIB-X9-GLB-015", "/scenes/" + defaultScene + "/nodes",
                        "Default scene reaches a node more than once");
            }
            pending.addAll(nodes.get(node).children());
        }
        if (active.size() != nodes.size()) {
            throw error("BLENDLIB-X9-GLB-015", "/nodes",
                    "Every X9 node must be reachable from the default scene");
        }
        return new HierarchyInfo(Set.copyOf(active), hierarchyOrder);
    }

    private void validateAcyclicAndDepthBounded(List<NodeInfo> nodes, int[] parents) {
        byte[] colors = new byte[nodes.size()];
        for (int start = 0; start < nodes.size(); start++) {
            if (colors[start] != 0) {
                continue;
            }
            ArrayDeque<TraversalFrame> stack = new ArrayDeque<>();
            colors[start] = 1;
            stack.push(new TraversalFrame(start));
            while (!stack.isEmpty()) {
                TraversalFrame frame = stack.peek();
                List<Integer> children = nodes.get(frame.node).children();
                if (frame.nextChild == children.size()) {
                    colors[frame.node] = 2;
                    stack.pop();
                    continue;
                }
                int child = children.get(frame.nextChild++);
                if (colors[child] == 1) {
                    throw error("BLENDLIB-X9-SCENE-004", "/nodes/" + frame.node + "/children",
                            "Node hierarchy cycle detected");
                }
                if (colors[child] == 0) {
                    colors[child] = 1;
                    stack.push(new TraversalFrame(child));
                }
            }
        }
        for (int rootIndex = 0; rootIndex < nodes.size(); rootIndex++) {
            if (parents[rootIndex] != -1) {
                continue;
            }
            ArrayDeque<NodeDepth> pending = new ArrayDeque<>();
            pending.add(new NodeDepth(rootIndex, 1));
            while (!pending.isEmpty()) {
                NodeDepth current = pending.removeFirst();
                if (current.depth() > limits.baseGlbLimits().maxHierarchyDepth()) {
                    throw error("BLENDLIB-X9-LIMIT-001", "/nodes/" + current.node(),
                            "Node hierarchy depth limit exceeded");
                }
                for (int child : nodes.get(current.node()).children()) {
                    pending.addLast(new NodeDepth(child, current.depth() + 1));
                }
            }
        }
    }

    private List<SkinInfo> validateSkins(List<NodeInfo> nodes, HierarchyInfo hierarchy) {
        JsonArray skinArray = array(required(root, "skins", "/skins"), "/skins");
        if (skinArray.size() == 0 || skinArray.size() > nodes.size()) {
            throw error(skinArray.size() == 0 ? "BLENDLIB-X9-SKIN-001" : "BLENDLIB-X9-LIMIT-001",
                    "/skins", "X9 skin count is outside bounds");
        }
        List<SkinInfo> skins = new ArrayList<>(skinArray.size());
        for (int index = 0; index < skinArray.size(); index++) {
            String pointer = "/skins/" + index;
            JsonObject skin = object(skinArray.get(index), pointer);
            rejectUnknown(skin, SKIN_FIELDS, pointer);
            validateOptionalString(skin, "name", pointer + "/name");
            List<Integer> joints = boundedIntegerList(
                    array(required(skin, "joints", pointer + "/joints"), pointer + "/joints"),
                    limits.baseGlbLimits().maxSkinJoints(), pointer + "/joints",
                    "Skin joint count exceeds the X9 bound");
            if (joints.isEmpty()) {
                throw error("BLENDLIB-X9-SKIN-001", pointer + "/joints", "Skin joint list must not be empty");
            }
            if (new HashSet<>(joints).size() != joints.size()) {
                throw error("BLENDLIB-X9-SKIN-001", pointer + "/joints", "Skin joints must be unique");
            }
            for (int joint : joints) {
                if (joint < 0 || joint >= nodes.size() || !hierarchy.activeNodes().contains(joint)) {
                    throw error("BLENDLIB-X9-SKIN-001", pointer + "/joints",
                            "Skin joint must reference an active node");
                }
            }
            int skeleton = nonNegativeInteger(required(skin, "skeleton", pointer + "/skeleton"), pointer + "/skeleton");
            if (skeleton >= nodes.size() || !hierarchy.activeNodes().contains(skeleton)) {
                throw error("BLENDLIB-X9-SKIN-001", pointer + "/skeleton",
                        "Skin skeleton must reference an active node");
            }
            if (joints.stream().anyMatch(joint -> !hierarchy.order().contains(skeleton, joint))) {
                throw error("BLENDLIB-X9-SKIN-001", pointer + "/joints",
                        "Every skin joint must descend from the declared skeleton root");
            }
            int inverseBind = nonNegativeInteger(
                    required(skin, "inverseBindMatrices", pointer + "/inverseBindMatrices"),
                    pointer + "/inverseBindMatrices");
            AccessorInfo inverseInfo = accessors.info(inverseBind);
            if (!"MAT4".equals(inverseInfo.type()) || inverseInfo.componentType() != FLOAT
                    || inverseInfo.normalized() || inverseInfo.count() != joints.size()) {
                throw error("BLENDLIB-X9-SKIN-001", pointer + "/inverseBindMatrices",
                        "Skin inverse-bind accessor must be non-normalized FLOAT MAT4, one per joint");
            }
            validateInverseBindMatrices(
                    accessors.readFloatElements(inverseBind, "MAT4"), joints.size(), pointer + "/inverseBindMatrices");
            skins.add(new SkinInfo(skeleton, List.copyOf(joints)));
        }
        return skins;
    }

    private static void validateInverseBindMatrices(float[] values, int matrixCount, String pointer) {
        for (int matrix = 0; matrix < matrixCount; matrix++) {
            int offset = matrix * 16;
            if (Math.abs(values[offset + 3]) > 1.0e-5f
                    || Math.abs(values[offset + 7]) > 1.0e-5f
                    || Math.abs(values[offset + 11]) > 1.0e-5f
                    || Math.abs(values[offset + 15] - 1.0f) > 1.0e-5f) {
                throw error("BLENDLIB-X9-SKIN-001", pointer,
                        "Inverse-bind matrices must be affine");
            }
            double m00 = values[offset];
            double m01 = values[offset + 4];
            double m02 = values[offset + 8];
            double m10 = values[offset + 1];
            double m11 = values[offset + 5];
            double m12 = values[offset + 9];
            double m20 = values[offset + 2];
            double m21 = values[offset + 6];
            double m22 = values[offset + 10];
            double determinant = m00 * (m11 * m22 - m12 * m21)
                    - m01 * (m10 * m22 - m12 * m20)
                    + m02 * (m10 * m21 - m11 * m20);
            if (!Double.isFinite(determinant) || Math.abs(determinant) <= 1.0e-12) {
                throw error("BLENDLIB-X9-SKIN-001", pointer,
                        "Inverse-bind matrices must be non-singular");
            }
        }
    }

    private static HierarchyOrder indexHierarchy(List<NodeInfo> nodes, int[] parents) {
        int[] entered = new int[nodes.size()];
        int[] exited = new int[nodes.size()];
        int clock = 0;
        for (int root = 0; root < nodes.size(); root++) {
            if (parents[root] != -1) {
                continue;
            }
            ArrayDeque<TraversalFrame> stack = new ArrayDeque<>();
            entered[root] = clock++;
            stack.push(new TraversalFrame(root));
            while (!stack.isEmpty()) {
                TraversalFrame frame = stack.peek();
                List<Integer> children = nodes.get(frame.node).children();
                if (frame.nextChild == children.size()) {
                    exited[frame.node] = clock++;
                    stack.pop();
                    continue;
                }
                int child = children.get(frame.nextChild++);
                entered[child] = clock++;
                stack.push(new TraversalFrame(child));
            }
        }
        return new HierarchyOrder(entered, exited);
    }

    private void validateNodeSkinAndMeshBindings(
            List<NodeInfo> nodes, List<SkinInfo> skins, Set<Integer> activeNodes, int meshCount) {
        int[] meshBindings = new int[meshCount];
        int[] skinBindings = new int[skins.size()];
        for (NodeInfo node : nodes) {
            if (node.skinIndex() >= 0 && node.meshIndex() < 0) {
                throw error("BLENDLIB-X9-SKIN-001", "/nodes/" + node.index() + "/skin",
                        "A skin reference requires a mesh node");
            }
            if (node.meshIndex() >= 0) {
                if (!activeNodes.contains(node.index())) {
                    throw error("BLENDLIB-X9-GLB-015", "/nodes/" + node.index() + "/mesh",
                            "Mesh node must be active");
                }
                if (node.skinIndex() < 0 || node.skinIndex() >= skins.size()) {
                    throw error("BLENDLIB-X9-SKIN-001", "/nodes/" + node.index() + "/skin",
                            "Every X9 mesh node must bind a valid skin");
                }
                meshBindings[node.meshIndex()]++;
                skinBindings[node.skinIndex()]++;
            } else if (!node.weights().isEmpty()) {
                throw error("BLENDLIB-X9-GLB-015", "/nodes/" + node.index() + "/weights",
                        "Morph weights require a mesh node");
            }
        }
        for (int mesh = 0; mesh < meshBindings.length; mesh++) {
            if (meshBindings[mesh] != 1) {
                throw error("BLENDLIB-X9-GLB-015", "/meshes/" + mesh,
                        "Every X9 mesh must bind to exactly one active node");
            }
        }
        for (int skin = 0; skin < skinBindings.length; skin++) {
            if (skinBindings[skin] == 0) {
                throw error("BLENDLIB-X9-SKIN-001", "/skins/" + skin,
                        "Every X9 skin must bind to an active mesh node");
            }
        }
    }

    private static List<Double> finiteArray(JsonArray array, int expected, String pointer) {
        if (array.size() != expected) {
            throw error("BLENDLIB-X9-GLB-015", pointer, "JSON vector/matrix cardinality is invalid");
        }
        return finiteList(array, pointer);
    }

    private static List<Double> finiteList(JsonArray array, String pointer) {
        List<Double> result = new ArrayList<>(array.size());
        for (int index = 0; index < array.size(); index++) {
            result.add(finite(number(array.get(index), pointer + "/" + index), pointer + "/" + index));
        }
        return List.copyOf(result);
    }

    private static List<Double> boundedFiniteList(
            JsonArray array, int maximum, String pointer, String message) {
        if (array.size() > maximum) {
            throw error("BLENDLIB-X9-LIMIT-001", pointer, message);
        }
        return finiteList(array, pointer);
    }

    private static List<Integer> integerList(JsonArray array, String pointer) {
        List<Integer> result = new ArrayList<>(array.size());
        for (int index = 0; index < array.size(); index++) {
            result.add(nonNegativeInteger(array.get(index), pointer + "/" + index));
        }
        return List.copyOf(result);
    }

    private static List<Integer> boundedIntegerList(
            JsonArray array, int maximum, String pointer, String message) {
        if (array.size() > maximum) {
            throw error("BLENDLIB-X9-LIMIT-001", pointer, message);
        }
        return integerList(array, pointer);
    }

    private static int optionalIndex(JsonObject object, String field, int fallback, String pointer) {
        return object.containsKey(field) ? nonNegativeInteger(object.get(field), pointer) : fallback;
    }

    private static void validateOptionalString(JsonObject object, String field, String pointer) {
        if (object.containsKey(field)) {
            string(object.get(field), pointer);
        }
    }

    private static JsonValue required(JsonObject object, String field, String pointer) {
        JsonValue value = object.get(field);
        if (value == null) {
            throw error("BLENDLIB-X9-GLB-002", pointer, "Required GLB field is missing");
        }
        return value;
    }

    private static void rejectUnknown(JsonObject object, Set<String> allowed, String pointer) {
        for (String field : object.values().keySet()) {
            if (!allowed.contains(field)) {
                throw error("BLENDLIB-X9-GLB-015", pointer + "/" + escape(field),
                        "Unknown field is outside the strict X9 GLB envelope");
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

    private static int nonNegativeInteger(JsonValue value, String pointer) {
        try {
            int result = number(value, pointer).asIntExact();
            if (result < 0) {
                throw new IllegalArgumentException("negative");
            }
            return result;
        } catch (IllegalArgumentException exception) {
            throw error("BLENDLIB-X9-GLB-015", pointer, "Expected a non-negative 32-bit integer");
        }
    }

    private static double finite(JsonNumber number, String pointer) {
        try {
            double result = number.asDouble();
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
            GlbAccessorReader accessors,
            List<NodeInfo> nodes,
            List<SkinInfo> skins,
            Set<Integer> activeNodes) {
    }

    record NodeInfo(
            int index,
            int meshIndex,
            int skinIndex,
            List<Integer> children,
            boolean matrixDeclared,
            List<Double> weights) {
    }

    record SkinInfo(int skeleton, List<Integer> joints) {
    }

    private record HierarchyInfo(Set<Integer> activeNodes, HierarchyOrder order) {
    }

    private record HierarchyOrder(int[] entered, int[] exited) {
        private boolean contains(int root, int node) {
            return entered[root] <= entered[node] && exited[node] <= exited[root];
        }
    }

    private static final class TraversalFrame {
        private final int node;
        private int nextChild;

        private TraversalFrame(int node) {
            this.node = node;
        }
    }

    private record NodeDepth(int node, int depth) {
    }
}
