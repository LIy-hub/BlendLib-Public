package com.liy.blendlib.fabric.v262.model;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.AnimationRequest;
import com.liy.blendlib.api.ModelInstance;
import com.liy.blendlib.api.PlaybackMode;
import com.liy.blendlib.core.animation.runtime.AnimationController;
import com.liy.blendlib.core.animation.runtime.AnimationControllerDefinition;
import com.liy.blendlib.core.animation.runtime.AnimationCorrection;
import com.liy.blendlib.core.animation.runtime.AnimationState;
import com.liy.blendlib.core.animation.runtime.NodePalette;
import com.liy.blendlib.core.animation.runtime.PoseSampler;
import com.liy.blendlib.core.descriptor.MaterialDefinition;
import com.liy.blendlib.core.model.MeshPrimitive;
import com.liy.blendlib.core.model.ModelAsset;
import com.liy.blendlib.core.model.ModelNode;
import com.liy.blendlib.core.model.ModelPrimitive;
import com.liy.blendlib.core.model.ModelProfile;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import com.liy.blendlib.fabric.v262.diagnostic.Fabric262Diagnostic;
import com.liy.blendlib.fabric.v262.resource.Fabric262MinecraftResourceAccess;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

/**
 * Immutable, reload-prepared standard rendering payload for Minecraft 26.2 Fabric.
 *
 * <p><strong>Platform target:</strong> Fabric on Minecraft 26.2. The currently implemented standard
 * path accepts strict {@code rigid_v1} opaque, single-sided, non-emissive assets and copies all
 * geometry before publication. Each copied primitive retains its original node index and resolves
 * it through a generation-bound immutable world-transform palette. This keeps per-node geometry,
 * descriptor {@code units_per_block}, and reload identity intact without consulting core assets on
 * the submit path. Unsupported required rendering profiles never reach submit as partially
 * initialized state: they become an explicit diagnostic-backed fallback handle.</p>
 */
public final class Fabric262PreparedModelHandle {
    private static final int FALLBACK_LIGHT = 0x00F000F0;
    private static final Identifier FALLBACK_TEXTURE = Identifier.fromNamespaceAndPath(
            "minecraft", "textures/misc/white.png");

    private final BlendModelKey modelKey;
    private final long generation;
    private final PreparedNodePalette nodePalette;
    private final Fabric262PoseSnapshot restPose;
    private final PreparedAnimationProgram animationProgram;
    private final List<PreparedPrimitive> primitives;
    private final Fabric262Diagnostic fallbackDiagnostic;

    private Fabric262PreparedModelHandle(
            BlendModelKey modelKey,
            long generation,
            PreparedNodePalette nodePalette,
            List<PreparedPrimitive> primitives,
            PreparedAnimationProgram animationProgram,
            Fabric262Diagnostic fallbackDiagnostic) {
        this.modelKey = Objects.requireNonNull(modelKey, "modelKey");
        if (generation < 0L) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
        this.generation = generation;
        this.nodePalette = nodePalette;
        this.primitives = List.copyOf(Objects.requireNonNull(primitives, "primitives"));
        this.animationProgram = animationProgram;
        this.fallbackDiagnostic = fallbackDiagnostic;
        if (fallbackDiagnostic == null && this.primitives.isEmpty()) {
            throw new IllegalArgumentException("ready handles require at least one prepared primitive");
        }
        if (fallbackDiagnostic == null) {
            PreparedNodePalette checkedPalette = Objects.requireNonNull(nodePalette, "nodePalette");
            checkedPalette.requireCompatible(this.modelKey, this.generation, this.primitives);
            this.restPose = checkedPalette.restPose();
        }
        if (fallbackDiagnostic != null && (!this.primitives.isEmpty() || nodePalette != null || animationProgram != null)) {
            throw new IllegalArgumentException("fallback handles cannot retain regular primitive or palette state");
        }
        if (fallbackDiagnostic != null) {
            this.restPose = Fabric262PoseSnapshot.fallback(this.modelKey, this.generation);
        }
    }

    /**
     * Prepares a detached immutable standard-rendering handle from one strict core asset.
     *
     * <p>Preparation runs during reload. It copies primitive arrays, freezes the model-node world
     * palette for this exact asset generation, and converts material resource identities at the
     * platform boundary. Later submit performs no descriptor loading, parsing, resource
     * resolution, provider discovery, or mutable node lookup.</p>
     *
     * @param asset decoded strict core model asset
     * @return immutable ready handle
     * @throws IllegalArgumentException when the asset needs a not-yet-integrated render capability
     */
    public static Fabric262PreparedModelHandle prepare(ModelAsset asset) {
        ModelAsset checkedAsset = Objects.requireNonNull(asset, "asset");
        BlendModelKey key = BlendModelKey.fromResourceId(checkedAsset.modelKey());
        if (checkedAsset.profile() != ModelProfile.RIGID_V1 || checkedAsset.skeleton() != null) {
            throw new IllegalArgumentException("Fabric 26.2 standard submit currently requires rigid_v1 without a skeleton");
        }
        PreparedNodePalette palette = PreparedNodePalette.prepare(
                key,
                checkedAsset.generation(),
                checkedAsset.nodes(),
                checkedAsset.defaultSceneRoots(),
                checkedAsset.unitsPerBlock());
        PreparedAnimationProgram animationProgram = PreparedAnimationProgram.prepare(
                key, checkedAsset.generation(), checkedAsset);
        List<PreparedPrimitive> prepared = new ArrayList<>();
        for (ModelPrimitive primitive : checkedAsset.primitives()) {
            MeshPrimitive geometry = primitive.geometry();
            MaterialDefinition material = checkedAsset.materials().get(geometry.materialSlot());
            if (material == null) {
                throw new IllegalArgumentException("Strict primitive has no mapped descriptor material: " + geometry.materialSlot());
            }
            if (material.mode() != MaterialDefinition.Mode.OPAQUE || material.doubleSided() || material.emissive()) {
                throw new IllegalArgumentException(
                        "Fabric 26.2 standard submit requires opaque, single-sided, non-emissive materials");
            }
            palette.transformFor(primitive.nodeIndex());
            prepared.add(new PreparedPrimitive(
                    primitive.nodeIndex(),
                    Fabric262MinecraftResourceAccess.toIdentifier(material.baseColor()),
                    geometry.positions(), geometry.normals(), geometry.texCoords(), geometry.indices()));
        }
        return new Fabric262PreparedModelHandle(
                key, checkedAsset.generation(), palette, prepared, animationProgram, null);
    }

    /**
     * Creates a safe diagnostic fallback for an unavailable or unsupported model generation.
     *
     * @param modelKey semantic model key
     * @param generation published generation number
     * @param diagnostic explicit root-cause diagnostic
     * @return immutable fallback-only handle
     */
    public static Fabric262PreparedModelHandle missing(
            BlendModelKey modelKey,
            long generation,
            Fabric262Diagnostic diagnostic) {
        return new Fabric262PreparedModelHandle(modelKey, generation, null, List.of(), null,
                Objects.requireNonNull(diagnostic, "diagnostic"));
    }

    /** Returns the semantic model identity represented by this immutable handle. */
    public BlendModelKey modelKey() {
        return modelKey;
    }

    /** Returns the reload generation that created this handle. */
    public long generation() {
        return generation;
    }

    /** Reports whether this handle will emit diagnostic fallback geometry. */
    public boolean isFallback() {
        return fallbackDiagnostic != null;
    }

    /** Returns the diagnostic that forced fallback, if any. */
    public Fabric262Diagnostic fallbackDiagnostic() {
        return fallbackDiagnostic;
    }

    /**
     * Returns the immutable strict rest-pose palette for this exact model generation.
     *
     * <p>Extraction uses this safe fallback when a registration animation request is unavailable
     * or invalid. Submit still receives a generation-bound immutable palette in that case.</p>
     */
    public Fabric262PoseSnapshot restPose() {
        return restPose;
    }

    /**
     * Creates one extraction-owned core controller bound to an exact platform-private instance.
     *
     * <p>Controller definition compilation is completed by {@link #prepare(ModelAsset)}; this
     * method neither reads resources nor parses model data. A requested state, playback intent,
     * speed, and transition are projected into a short-lived immutable definition without
     * mutating the generation-wide prepared program.</p>
     *
     * @param instance exact entity, block-entity, or stateless item model instance
     * @param request immutable semantic request evaluated by the stable registration source
     * @return a new mutable controller owned by exactly one host instance
     */
    public AnimationController createAnimationController(ModelInstance instance, AnimationRequest request) {
        return createAnimationController(instance, request, null);
    }

    /**
     * Rebinds a same-generation host request while preserving its prior sampled state as the
     * source of the request's configured transition. Reload/model replacement callers pass
     * {@code null}, so no controller or pose can cross a generation boundary.
     */
    public AnimationController createAnimationController(
            ModelInstance instance,
            AnimationRequest request,
            AnimationController previousController) {
        ModelInstance checkedInstance = Objects.requireNonNull(instance, "instance");
        if (!modelKey.equals(checkedInstance.modelKey()) || generation != checkedInstance.resourceGeneration()) {
            throw new IllegalArgumentException("Animation controller cannot cross a Fabric 26.2 model generation");
        }
        if (animationProgram == null) {
            throw new IllegalStateException("Prepared Fabric 26.2 model has no compiled animation declaration");
        }
        return animationProgram.createController(
                checkedInstance,
                Objects.requireNonNull(request, "request"),
                previousController);
    }

    /**
     * Advances one extraction-owned controller and freezes its canonical rigid palette.
     *
     * @param controller controller created for this exact handle generation
     * @param deltaSeconds finite non-negative extraction-clock delta
     * @return immutable pose consumed later by submit
     */
    public Fabric262PoseSnapshot advanceAndFreeze(AnimationController controller, double deltaSeconds) {
        if (animationProgram == null) {
            throw new IllegalStateException("Prepared Fabric 26.2 model has no compiled animation declaration");
        }
        return animationProgram.advanceAndFreeze(Objects.requireNonNull(controller, "controller"), deltaSeconds);
    }

    /**
     * Samples one stateless loop-only item pose from an observed monotonic extraction time.
     *
     * <p>No item stack, world, or renderer object is retained. The bounded phase is calculated
     * before controller advance so a long-running process cannot turn its elapsed clock into an
     * unbounded controller walk.</p>
     */
    public Fabric262PoseSnapshot sampleStatelessLoop(
            ModelInstance instance,
            AnimationRequest request,
            double observedSeconds) {
        ModelInstance checkedInstance = Objects.requireNonNull(instance, "instance");
        if (checkedInstance.instanceKey() != com.liy.blendlib.api.BlendInstanceKey.Item.STATELESS) {
            throw new IllegalArgumentException("Fabric 26.2 stateless item sampling requires Item.STATELESS");
        }
        if (!modelKey.equals(checkedInstance.modelKey()) || generation != checkedInstance.resourceGeneration()) {
            throw new IllegalArgumentException("Stateless item sampling cannot cross a Fabric 26.2 model generation");
        }
        if (animationProgram == null) {
            throw new IllegalStateException("Prepared Fabric 26.2 model has no compiled animation declaration");
        }
        return animationProgram.sampleStatelessLoop(
                checkedInstance, Objects.requireNonNull(request, "request"), observedSeconds);
    }

    /**
     * Submits copied immutable geometry through the public 26.2 collector API.
     *
     * <p>This is a hot-path method. It intentionally accepts only a prepared handle, caller-owned
     * pose/collector, and immutable frame state; it has no resource manager, GLB parser, JSON
     * decoder, filesystem, provider-discovery, reflection, or raw OpenGL dependency. Every
     * primitive applies its own frozen node world transform and its descriptor unit conversion
     * before crossing into the caller-owned pose.</p>
     *
     * @param poseStack caller-owned pose stack
     * @param collector public Minecraft 26.2 submit collector
     * @param frame immutable per-submit light, overlay, and tint values
     */
    public void submit(PoseStack poseStack, SubmitNodeCollector collector, Fabric262FrameState frame) {
        submit(poseStack, collector, frame, restPose);
    }

    /**
     * Submits copied immutable geometry using the exact extraction-frozen rigid-node palette.
     *
     * @param poseStack caller-owned pose stack
     * @param collector public Minecraft 26.2 submit collector
     * @param frame immutable per-submit light, overlay, and tint values
     * @param pose extraction-frozen palette from this exact model generation
     */
    public void submit(
            PoseStack poseStack,
            SubmitNodeCollector collector,
            Fabric262FrameState frame,
            Fabric262PoseSnapshot pose) {
        PoseStack checkedPoseStack = Objects.requireNonNull(poseStack, "poseStack");
        SubmitNodeCollector checkedCollector = Objects.requireNonNull(collector, "collector");
        Fabric262FrameState checkedFrame = Objects.requireNonNull(frame, "frame");
        Fabric262PoseSnapshot checkedPose = Objects.requireNonNull(pose, "pose");
        if (fallbackDiagnostic != null) {
            submitFallback(checkedPoseStack, checkedCollector, checkedFrame);
            return;
        }
        PreparedNodePalette palette = Objects.requireNonNull(nodePalette, "nodePalette");
        palette.requireCompatible(modelKey, generation, primitives);
        checkedPose.requireCompatible(modelKey, generation);
        for (PreparedPrimitive primitive : primitives) {
            Transform worldTransform = checkedPose.transformFor(primitive.nodeIndex);
            checkedCollector.submitCustomGeometry(
                    checkedPoseStack,
                    RenderTypes.entitySolid(primitive.texture),
                    (submittedPose, consumer) -> primitive.emit(
                            submittedPose,
                            consumer,
                            checkedFrame,
                            worldTransform,
                            checkedPose.unitsToBlocksScale()));
        }
    }

    private void submitFallback(PoseStack poseStack, SubmitNodeCollector collector, Fabric262FrameState frame) {
        collector.submitCustomGeometry(poseStack, RenderTypes.entitySolid(FALLBACK_TEXTURE), (pose, consumer) -> {
            emitFallbackVertex(pose, consumer, -0.25f, -0.25f, 0.0f, 0.0f, 0.0f, frame);
            emitFallbackVertex(pose, consumer, 0.25f, -0.25f, 0.0f, 1.0f, 0.0f, frame);
            emitFallbackVertex(pose, consumer, 0.0f, 0.25f, 0.0f, 0.5f, 1.0f, frame);
        });
    }

    private static void emitFallbackVertex(
            PoseStack.Pose pose,
            VertexConsumer consumer,
            float x,
            float y,
            float z,
            float u,
            float v,
            Fabric262FrameState frame) {
        consumer.addVertex(pose, x, y, z)
                .setColor(0xFFFF00FF)
                .setUv(u, v)
                .setOverlay(frame.packedOverlay())
                .setLight(FALLBACK_LIGHT)
                .setNormal(pose, 0.0f, 0.0f, 1.0f);
    }

    /**
     * Reload-prepared pure-core animation payload for one strict model generation.
     *
     * <p>The payload is immutable and shared only as clip/sampler configuration. Every native
     * entity or block entity receives a separate {@link AnimationController}; it is never stored
     * here. The item path constructs a transient controller for {@code Item.STATELESS} only.</p>
     */
    private static final class PreparedAnimationProgram {
        private final BlendModelKey modelKey;
        private final long generation;
        private final AnimationControllerDefinition definition;
        private final PoseSampler sampler;
        private final List<ModelNode> nodes;
        private final List<Integer> defaultSceneRoots;
        private final float unitsToBlocksScale;

        private PreparedAnimationProgram(
                BlendModelKey modelKey,
                long generation,
                AnimationControllerDefinition definition,
                PoseSampler sampler,
                List<ModelNode> nodes,
                List<Integer> defaultSceneRoots,
                float unitsToBlocksScale) {
            this.modelKey = Objects.requireNonNull(modelKey, "modelKey");
            this.generation = generation;
            this.definition = Objects.requireNonNull(definition, "definition");
            this.sampler = Objects.requireNonNull(sampler, "sampler");
            this.nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
            this.defaultSceneRoots = List.copyOf(Objects.requireNonNull(defaultSceneRoots, "defaultSceneRoots"));
            this.unitsToBlocksScale = unitsToBlocksScale;
        }

        private static PreparedAnimationProgram prepare(
                BlendModelKey modelKey,
                long generation,
                ModelAsset asset) {
            if (asset.animationDefinition() == null) {
                return null;
            }
            return new PreparedAnimationProgram(
                    modelKey,
                    generation,
                    AnimationControllerDefinition.fromModelAsset(asset),
                    PoseSampler.fromModelAsset(asset),
                    asset.nodes(),
                    asset.defaultSceneRoots(),
                    PreparedNodePalette.unitsToBlocksScale(asset.unitsPerBlock()));
        }

        private AnimationController createController(
                ModelInstance instance,
                AnimationRequest request,
                AnimationController previousController) {
            if (!modelKey.equals(instance.modelKey()) || generation != instance.resourceGeneration()) {
                throw new IllegalArgumentException("Prepared animation program cannot cross a model generation");
            }
            com.liy.blendlib.api.BlendAnimationKey initialState = previousController == null
                    ? request.animation()
                    : previousController.currentState();
            AnimationController replacement = new AnimationController(
                    instance.instanceKey(), definitionFor(request, initialState));
            if (previousController != null) {
                replacement.applyCorrection(new AnimationCorrection(
                        initialState,
                        previousController.currentTimeSeconds(),
                        0L,
                        0.0d));
                if (!initialState.equals(request.animation())) {
                    replacement.trigger(request.animation());
                }
            }
            return replacement;
        }

        private Fabric262PoseSnapshot advanceAndFreeze(AnimationController controller, double deltaSeconds) {
            controller.advance(deltaSeconds);
            return freeze(controller);
        }

        private Fabric262PoseSnapshot sampleStatelessLoop(
                ModelInstance instance,
                AnimationRequest request,
                double observedSeconds) {
            if (!Double.isFinite(observedSeconds) || observedSeconds < 0.0d) {
                throw new IllegalArgumentException("Observed item animation time must be finite and non-negative");
            }
            if (request.playbackMode() != PlaybackMode.LOOP) {
                throw new IllegalArgumentException("Fabric 26.2 stateless items require LOOP playback");
            }
            AnimationControllerDefinition projected = definitionFor(request, request.animation());
            AnimationState state = projected.initialStateDefinition();
            double duration = state.clip().durationSeconds();
            double phaseSeconds = 0.0d;
            if (duration > 0.0d) {
                double periodSeconds = duration / state.speed();
                if (!Double.isFinite(periodSeconds) || periodSeconds <= 0.0d) {
                    throw new IllegalArgumentException("Stateless item animation has an invalid loop period");
                }
                phaseSeconds = observedSeconds % periodSeconds;
            }
            AnimationController controller = new AnimationController(instance.instanceKey(), projected);
            controller.advance(phaseSeconds);
            return freeze(controller);
        }

        private Fabric262PoseSnapshot freeze(AnimationController controller) {
            NodePalette palette = NodePalette.fromCanonicalScene(
                    controller.sample(sampler), nodes, defaultSceneRoots);
            return Fabric262PoseSnapshot.regular(
                    modelKey, generation, palette.worldTransforms(), unitsToBlocksScale);
        }

        private AnimationControllerDefinition definitionFor(
                AnimationRequest request,
                com.liy.blendlib.api.BlendAnimationKey initialState) {
            AnimationState requested = definition.state(request.animation());
            double projectedSpeed = requested.speed() * request.speed();
            if (!Double.isFinite(projectedSpeed) || projectedSpeed <= 0.0d) {
                throw new IllegalArgumentException("Requested Fabric 26.2 animation speed is not representable");
            }
            double transitionSeconds = request.transition().toNanos() / 1_000_000_000.0d;
            if (!Double.isFinite(transitionSeconds) || transitionSeconds < 0.0d) {
                throw new IllegalArgumentException("Requested Fabric 26.2 animation transition is not representable");
            }
            LinkedHashMap<com.liy.blendlib.api.BlendAnimationKey, AnimationState> projected = new LinkedHashMap<>();
            for (Map.Entry<com.liy.blendlib.api.BlendAnimationKey, AnimationState> entry : definition.states().entrySet()) {
                AnimationState state = entry.getValue();
                if (!entry.getKey().equals(request.animation())) {
                    projected.put(entry.getKey(), state);
                    continue;
                }
                boolean loop = request.playbackMode() == PlaybackMode.LOOP;
                com.liy.blendlib.api.BlendAnimationKey next = request.playbackMode() == PlaybackMode.ONCE
                        ? state.next()
                        : null;
                double appliedTransition = initialState.equals(request.animation())
                        ? 0.0d
                        : transitionSeconds;
                projected.put(entry.getKey(), new AnimationState(
                        state.key(),
                        state.clip(),
                        loop,
                        projectedSpeed,
                        appliedTransition,
                        next,
                        state.events()));
            }
            return new AnimationControllerDefinition(initialState, projected);
        }
    }

    /** Private copied primitive data; no source geometry array is retained after preparation. */
    private static final class PreparedPrimitive {
        private final int nodeIndex;
        private final Identifier texture;
        private final float[] positions;
        private final float[] normals;
        private final float[] texCoords;
        private final int[] indices;

        private PreparedPrimitive(
                int nodeIndex,
                Identifier texture,
                float[] positions,
                float[] normals,
                float[] texCoords,
                int[] indices) {
            if (nodeIndex < 0) {
                throw new IllegalArgumentException("Prepared primitive nodeIndex must be non-negative");
            }
            this.nodeIndex = nodeIndex;
            this.texture = Objects.requireNonNull(texture, "texture");
            this.positions = copyFinite(positions, "positions");
            this.normals = copyFinite(normals, "normals");
            this.texCoords = copyFinite(texCoords, "texCoords");
            this.indices = Arrays.copyOf(Objects.requireNonNull(indices, "indices"), indices.length);
            if (this.positions.length == 0 || this.positions.length % 3 != 0
                    || this.normals.length != this.positions.length
                    || this.texCoords.length != vertexCount() * 2
                    || this.indices.length == 0 || this.indices.length % 3 != 0) {
                throw new IllegalArgumentException("Prepared primitive violates strict triangle cardinality");
            }
            for (int index : this.indices) {
                if (index < 0 || index >= vertexCount()) {
                    throw new IllegalArgumentException("Prepared primitive index is outside the vertex range");
                }
            }
        }

        private int vertexCount() {
            return positions.length / 3;
        }

        private void emit(
                PoseStack.Pose pose,
                VertexConsumer consumer,
                Fabric262FrameState frame,
                Transform worldTransform,
                float unitsToBlocksScale) {
            for (int index : indices) {
                int positionOffset = index * 3;
                int textureOffset = index * 2;
                Vec3 convertedPosition = worldTransform.transformPoint(new Vec3(
                        positions[positionOffset], positions[positionOffset + 1], positions[positionOffset + 2]))
                        .multiply(unitsToBlocksScale);
                Vec3 convertedNormal = worldTransform.rotation().rotate(new Vec3(
                        normals[positionOffset], normals[positionOffset + 1], normals[positionOffset + 2]));
                consumer.addVertex(pose, convertedPosition.x(), convertedPosition.y(), convertedPosition.z())
                        .setColor(frame.argbTint())
                        .setUv(texCoords[textureOffset], texCoords[textureOffset + 1])
                        .setOverlay(frame.packedOverlay())
                        .setLight(frame.packedLight())
                        .setNormal(pose, convertedNormal.x(), convertedNormal.y(), convertedNormal.z());
            }
        }

        private static float[] copyFinite(float[] source, String name) {
            float[] copy = Arrays.copyOf(Objects.requireNonNull(source, name), source.length);
            for (float value : copy) {
                if (!Float.isFinite(value)) {
                    throw new IllegalArgumentException(name + " must contain finite values");
                }
            }
            return copy;
        }
    }

    /**
     * Immutable rest-pose node palette owned by exactly one prepared model generation.
     *
     * <p>The palette mirrors the strict core hierarchy rules while retaining no mutable core
     * asset reference. It deliberately applies model-unit conversion after the complete node
     * world transform, so node translation and primitive coordinates use one audited scale.</p>
     */
    private static final class PreparedNodePalette {
        private final BlendModelKey modelKey;
        private final long generation;
        private final List<Transform> worldTransforms;
        private final float unitsToBlocksScale;

        private PreparedNodePalette(
                BlendModelKey modelKey,
                long generation,
                List<Transform> worldTransforms,
                float unitsToBlocksScale) {
            this.modelKey = Objects.requireNonNull(modelKey, "modelKey");
            if (generation < 0L) {
                throw new IllegalArgumentException("generation must be non-negative");
            }
            this.generation = generation;
            // A canonical glTF scene can select a root whose structural parent belongs to another
            // scene. Keep entries outside the selected scene as null so they cannot accidentally
            // receive a primitive, rather than rejecting the complete strict asset during copying.
            this.worldTransforms = Collections.unmodifiableList(new ArrayList<>(
                    Objects.requireNonNull(worldTransforms, "worldTransforms")));
            this.unitsToBlocksScale = unitsToBlocksScale;
            if (!Float.isFinite(unitsToBlocksScale) || unitsToBlocksScale <= 0.0f) {
                throw new IllegalArgumentException("unitsToBlocksScale must be positive and finite");
            }
        }

        private static PreparedNodePalette prepare(
                BlendModelKey modelKey,
                long generation,
                List<ModelNode> nodes,
                List<Integer> defaultSceneRoots,
                double unitsPerBlock) {
            List<ModelNode> checkedNodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
            if (checkedNodes.isEmpty()) {
                throw new IllegalArgumentException("A strict rigid model needs at least one node");
            }
            int[] parents = new int[checkedNodes.size()];
            Arrays.fill(parents, -1);
            for (int index = 0; index < checkedNodes.size(); index++) {
                ModelNode node = checkedNodes.get(index);
                if (node.index() != index) {
                    throw new IllegalArgumentException("Model nodes must be contiguous before Fabric 26.2 preparation");
                }
                for (int child : node.children()) {
                    if (child < 0 || child >= checkedNodes.size() || parents[child] != -1) {
                        throw new IllegalArgumentException("Model nodes must form one valid parent hierarchy");
                    }
                    parents[child] = index;
                }
            }
            List<Integer> roots = List.copyOf(Objects.requireNonNull(defaultSceneRoots, "defaultSceneRoots"));
            if (roots.isEmpty()) {
                throw new IllegalArgumentException("A strict rigid model needs declared default-scene roots");
            }
            Transform[] world = new Transform[checkedNodes.size()];
            ArrayDeque<Integer> pending = new ArrayDeque<>();
            for (int root : roots) {
                if (root < 0 || root >= checkedNodes.size() || world[root] != null) {
                    throw new IllegalArgumentException("Default-scene roots must be unique selected roots");
                }
                world[root] = checkedNodes.get(root).localTransform();
                pending.addLast(root);
            }
            while (!pending.isEmpty()) {
                int parent = pending.removeFirst();
                for (int child : checkedNodes.get(parent).children()) {
                    if (world[child] != null) {
                        throw new IllegalArgumentException("Model nodes must be acyclic before Fabric 26.2 preparation");
                    }
                    world[child] = world[parent].compose(checkedNodes.get(child).localTransform());
                    pending.addLast(child);
                }
            }
            float unitScale = unitsToBlocksScale(unitsPerBlock);
            return new PreparedNodePalette(modelKey, generation, Arrays.asList(world), unitScale);
        }

        private static float unitsToBlocksScale(double unitsPerBlock) {
            double scale = 1.0d / unitsPerBlock;
            float result = (float) scale;
            if (!Double.isFinite(unitsPerBlock) || unitsPerBlock <= 0.0d
                    || !Double.isFinite(scale) || !Float.isFinite(result) || result <= 0.0f) {
                throw new IllegalArgumentException("units_per_block cannot be represented as a positive finite render scale");
            }
            return result;
        }

        private Transform transformFor(int nodeIndex) {
            if (nodeIndex < 0 || nodeIndex >= worldTransforms.size()) {
                throw new IllegalArgumentException("Prepared primitive node index is outside the frozen node palette: " + nodeIndex);
            }
            Transform transform = worldTransforms.get(nodeIndex);
            if (transform == null) {
                throw new IllegalArgumentException(
                        "Prepared primitive node is not reachable from the strict default scene: " + nodeIndex);
            }
            return transform;
        }

        private Fabric262PoseSnapshot restPose() {
            LinkedHashMap<Integer, Transform> palette = new LinkedHashMap<>();
            for (int nodeIndex = 0; nodeIndex < worldTransforms.size(); nodeIndex++) {
                Transform transform = worldTransforms.get(nodeIndex);
                if (transform != null) {
                    palette.put(nodeIndex, transform);
                }
            }
            return Fabric262PoseSnapshot.regular(modelKey, generation, palette, unitsToBlocksScale);
        }

        private void requireCompatible(
                BlendModelKey expectedModelKey,
                long expectedGeneration,
                List<PreparedPrimitive> primitives) {
            if (!modelKey.equals(expectedModelKey) || generation != expectedGeneration) {
                throw new IllegalStateException("Prepared node palette cannot cross a model key or resource generation");
            }
            for (PreparedPrimitive primitive : primitives) {
                transformFor(primitive.nodeIndex);
            }
        }
    }
}
