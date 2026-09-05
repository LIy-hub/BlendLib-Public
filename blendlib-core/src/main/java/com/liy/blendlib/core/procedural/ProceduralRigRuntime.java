package com.liy.blendlib.core.procedural;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.api.ModelInstance;
import com.liy.blendlib.core.animation.v2.AnimationV2Pose;
import com.liy.blendlib.core.animation.v2.AnimationV2InstanceRuntime;
import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single-owner X3 evaluator. It reads one already-published X2 evaluation snapshot and never advances or mutates X2.
 */
public final class ProceduralRigRuntime {
    private final ProceduralRigPlan plan;
    private final ModelInstance scope;
    private final AnimationV2InstanceRuntime visualEventSourceRuntime;
    private final ProceduralVisualEventTimelineRegistry visualEventTimelineRegistry;
    private final AtomicReference<ProceduralFrameSnapshot> latest = new AtomicReference<>();
    private final LinkedHashSet<ProceduralVisualEventReplayIdentity> visualEventReplayFence = new LinkedHashSet<>();
    private Thread ownerThread;
    /** Advances only with a fully committed X3 snapshot, never during speculative frame validation. */
    private long highestPublishedRevision = -1L;

    /** Binds one runtime to one immutable {@link ModelInstance}; a reload or sibling actor needs a new runtime. */
    public ProceduralRigRuntime(ProceduralRigPlan plan, ModelInstance scope) {
        this(plan, scope, null, null);
    }

    /**
     * Binds an X3 rig to one exact X2 runtime. Configured marker events become usable only through this path: each
     * frame must pass that runtime's current immutable snapshot object and a real forward active-playhead crossing.
     */
    public ProceduralRigRuntime(
            ProceduralRigPlan plan,
            ModelInstance scope,
            AnimationV2InstanceRuntime visualEventSourceRuntime) {
        this(plan, scope, Objects.requireNonNull(visualEventSourceRuntime, "visualEventSourceRuntime"),
                ProceduralVisualEventTimelineRegistry.freeze(plan, visualEventSourceRuntime, plan.visualEventMarkers()));
    }

    private ProceduralRigRuntime(
            ProceduralRigPlan plan,
            ModelInstance scope,
            AnimationV2InstanceRuntime visualEventSourceRuntime,
            ProceduralVisualEventTimelineRegistry visualEventTimelineRegistry) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.scope = Objects.requireNonNull(scope, "scope");
        if ((visualEventSourceRuntime == null) != (visualEventTimelineRegistry == null)) {
            throw new IllegalArgumentException("visual event runtime and frozen marker registry must be bound together");
        }
        this.visualEventSourceRuntime = visualEventSourceRuntime;
        this.visualEventTimelineRegistry = visualEventTimelineRegistry;
        if (visualEventTimelineRegistry == null && !this.plan.visualEventMarkers().isEmpty()) {
            throw new IllegalArgumentException(
                    "plans with visual event markers require the three-argument runtime constructor and exact X2 binding");
        }
        if (visualEventTimelineRegistry != null && !visualEventTimelineRegistry.matches(plan, visualEventSourceRuntime)) {
            throw new IllegalArgumentException("visual event timeline registry does not belong to this exact X3/X2 runtime pair");
        }
        if (!scope.modelKey().equals(plan.modelKey()) || scope.resourceGeneration() != plan.generation()) {
            throw new IllegalArgumentException("runtime scope must match the frozen rig plan model and resource generation");
        }
        plan.attachmentGraph().requireRuntimePlan(plan.snapshotPlanIdentity());
    }

    public ProceduralRigPlan plan() {
        return plan;
    }

    /** The exact immutable instance accepted by this runtime. */
    public ModelInstance scope() {
        return scope;
    }

    public ProceduralFrameSnapshot latestSnapshot() {
        return plan.attachmentGraph().readIfActive(plan.snapshotPlanIdentity(), () -> {
            ProceduralFrameSnapshot snapshot = latest.get();
            if (snapshot == null) {
                throw new IllegalStateException("no procedural frame has been successfully published");
            }
            return snapshot;
        });
    }

    /** Evaluates and atomically publishes only a complete, generation-valid snapshot. */
    public ProceduralEvaluationResult evaluate(ProceduralFrameInput input) {
        claimOwner();
        Objects.requireNonNull(input, "input");
        ProceduralDiagnosticCollector diagnostics = new ProceduralDiagnosticCollector();
        ProceduralFrameSnapshot prior = latest.get();
        if (!plan.attachmentGraph().isActivePlan(plan.snapshotPlanIdentity())) {
            diagnostics.add(ProceduralDiagnosticSeverity.ERROR, ProceduralDiagnosticCode.ATTACHMENT_GRAPH_RETIRED,
                    plan.modelKey().resourceId(), "attachment graph retired before this frame could publish");
            return failed(prior, diagnostics);
        }
        try {
            if (!validateFrame(input, diagnostics)) {
                return failed(prior, diagnostics);
            }
            List<ProceduralDirective> directives = new ArrayList<>(input.directives());
            if (!runHooks(input, directives, diagnostics) || diagnostics.hasErrors()) {
                return failed(prior, diagnostics);
            }
            List<ProceduralDirective> canonical = canonicalize(directives, diagnostics);
            if (diagnostics.hasErrors()) {
                return failed(prior, diagnostics);
            }

            Transform[] local = readPose(input.evaluation().pose());
            applyOffsets(local, canonical);
            applyLookAt(local, canonical, diagnostics);
            applyRotationOffsets(local, canonical);
            List<Transform> modelTransforms = modelTransforms(local);
            boolean[] boneVisibility = resolveBoneVisibility(canonical);
            propagateParentVisibility(boneVisibility);
            Map<ProceduralSurfaceTarget, Boolean> surfaceVisibility = resolveSurfaceVisibility(canonical, boneVisibility);
            Map<ProceduralSurfaceTarget, SemanticSurfaceOverride> surfaceOverrides = resolveSurfaceOverrides(canonical);
            SocketState sockets = resolveSockets(modelTransforms, boneVisibility);
            List<ProceduralResolvedAttachment> attachments = resolveAttachments(input, modelTransforms, boneVisibility, sockets, diagnostics);
            if (diagnostics.hasErrors()) {
                return failed(prior, diagnostics);
            }
            ProceduralVisualEventTimelineRegistry.Resolution trustedEvents = null;
            ProceduralVisualEventBatch.Resolution resolvedEvents;
            if (visualEventTimelineRegistry == null) {
                resolvedEvents = new ProceduralVisualEventBatch.Resolution(ProceduralVisualEventBatch.empty(), List.of());
            } else {
                trustedEvents = visualEventTimelineRegistry.resolve(input.evaluation(), diagnostics);
                if (diagnostics.hasErrors()) {
                    return failed(prior, diagnostics);
                }
                resolvedEvents = ProceduralVisualEventBatch.resolveTrusted(trustedEvents, sockets.transforms(), diagnostics);
            }
            if (diagnostics.hasErrors()) {
                return failed(prior, diagnostics);
            }
            ProceduralVisualEventBatch.Resolution freshEvents = ProceduralVisualEventBatch.retainUnseen(
                    resolvedEvents, visualEventReplayFence, diagnostics);
            if (visualEventReplayFence.size() + freshEvents.identities().size()
                    > ProceduralLimits.MAX_VISUAL_EVENT_REPLAY_FENCE) {
                diagnostics.add(ProceduralDiagnosticSeverity.ERROR, ProceduralDiagnosticCode.VISUAL_EVENT_REPLAY_FENCE_OVERFLOW,
                        plan.modelKey().resourceId(), "visual event replay fence would exceed its immutable scope budget");
                return failed(prior, diagnostics);
            }
            AnimationV2Pose finalPose = new AnimationV2Pose(Arrays.asList(local.clone()));
            ProceduralFrameSnapshot snapshot = new ProceduralFrameSnapshot(
                    input.modelInstance(),
                    plan.snapshotPlanIdentity(),
                    input.evaluation().revision(),
                    finalPose,
                    modelTransforms,
                    toBooleanList(boneVisibility),
                    surfaceVisibility,
                    surfaceOverrides,
                    sockets.socketTransforms(),
                    sockets.hiddenIds(),
                    attachments,
                    freshEvents.batch(),
                    diagnostics.snapshot());
            ProceduralVisualEventTimelineRegistry.Resolution trustedEventsToCommit = trustedEvents;
            boolean committed = plan.attachmentGraph().commitIfActive(plan.snapshotPlanIdentity(), () -> {
                latest.set(snapshot);
                visualEventReplayFence.addAll(freshEvents.identities());
                if (trustedEventsToCommit != null) {
                    // Crossing observation and replay identity commit share the graph's final publication point.
                    visualEventTimelineRegistry.commit(trustedEventsToCommit);
                }
                highestPublishedRevision = input.evaluation().revision();
            });
            if (!committed) {
                diagnostics.add(ProceduralDiagnosticSeverity.ERROR, ProceduralDiagnosticCode.ATTACHMENT_GRAPH_RETIRED,
                        plan.modelKey().resourceId(), "attachment graph retired while this frame was evaluating");
                return failed(prior, diagnostics);
            }
            return new ProceduralEvaluationResult(true, snapshot, snapshot.diagnostics());
        } catch (RuntimeException exception) {
            diagnostics.exception(ProceduralDiagnosticCode.NONFINITE_INPUT, null, exception);
            return failed(prior, diagnostics);
        }
    }

    private void claimOwner() {
        synchronized (this) {
            Thread current = Thread.currentThread();
            if (ownerThread == null) {
                ownerThread = current;
            } else if (ownerThread != current) {
                throw new ProceduralOwnerThreadViolation();
            }
        }
    }

    private boolean validateFrame(ProceduralFrameInput input, ProceduralDiagnosticCollector diagnostics) {
        ModelInstance instance = input.modelInstance();
        if (!instance.equals(scope)) {
            diagnostics.add(ProceduralDiagnosticSeverity.ERROR, ProceduralDiagnosticCode.STALE_SCOPE,
                    instance.modelKey().resourceId(), "exact model instance does not match this frozen procedural runtime");
            return false;
        }
        if (!input.visualEvents().isEmpty()) {
            diagnostics.add(ProceduralDiagnosticSeverity.ERROR, ProceduralDiagnosticCode.VISUAL_EVENT_PROVENANCE,
                    instance.modelKey().resourceId(), "per-frame caller visual events are untrusted; use the frozen plan marker catalog and exact X2 runtime binding");
            return false;
        }
        if (visualEventSourceRuntime != null && !visualEventTimelineRegistry.accepts(input.evaluation())) {
            diagnostics.add(ProceduralDiagnosticSeverity.ERROR, ProceduralDiagnosticCode.VISUAL_EVENT_PROVENANCE,
                    instance.modelKey().resourceId(), "X3 event evaluation must use the exact current snapshot of its bound X2 runtime");
            return false;
        }
        if (input.evaluation().revision() <= highestPublishedRevision) {
            diagnostics.add(ProceduralDiagnosticSeverity.ERROR, ProceduralDiagnosticCode.STALE_REVISION,
                    instance.modelKey().resourceId(), "X2 evaluation revision must be strictly newer than this runtime watermark");
            return false;
        }
        if (input.evaluation().pose().boneCount() != plan.schema().boneCount()) {
            diagnostics.add(ProceduralDiagnosticSeverity.ERROR, ProceduralDiagnosticCode.CARDINALITY_MISMATCH,
                    plan.modelKey().resourceId(), "X2 pose cardinality does not match frozen rig schema");
            return false;
        }
        if (input.directiveOverflowOrFailure()) {
            diagnostics.add(ProceduralDiagnosticSeverity.ERROR, ProceduralDiagnosticCode.COMMAND_OVERFLOW,
                    null, "frame directive iterable exceeded its bounded capture budget or failed during capture");
        }
        if (input.attachmentOverflowOrFailure()) {
            diagnostics.add(ProceduralDiagnosticSeverity.ERROR, ProceduralDiagnosticCode.ATTACHMENT_LIMIT,
                    null, "attachment iterable exceeded its bounded capture budget or failed during capture");
        }
        if (input.visualEventOverflowOrFailure()) {
            diagnostics.add(ProceduralDiagnosticSeverity.ERROR, ProceduralDiagnosticCode.VISUAL_EVENT_OVERFLOW,
                    null, "visual-event iterable exceeded its bounded capture budget or failed during capture");
        }
        return !diagnostics.hasErrors();
    }

    private boolean runHooks(
            ProceduralFrameInput input,
            List<ProceduralDirective> directives,
            ProceduralDiagnosticCollector diagnostics) {
        for (ProceduralRigPlan.ProceduralHookBinding binding : plan.hooks()) {
            BoundedHookSink sink = new BoundedHookSink(binding.id(), binding.priority(), directives, diagnostics);
            try {
                binding.hook().contribute(new ProceduralHookContext(input.modelInstance(), input.evaluation()), sink);
            } catch (Throwable throwable) {
                rethrowFatalCallbackFailure(throwable);
                diagnostics.exception(ProceduralDiagnosticCode.HOOK_FAILURE, binding.id(), throwable);
            }
            if (diagnostics.hasErrors()) {
                return false;
            }
        }
        return true;
    }

    private static void rethrowFatalCallbackFailure(Throwable throwable) {
        // Extension assertion failures are deliberately contained, but VM/linkage/termination-style Errors must
        // remain visible to the host rather than being mistaken for a normal frame diagnostic.
        if (throwable instanceof Error fatal && !(fatal instanceof AssertionError)) {
            throw fatal;
        }
    }

    private List<ProceduralDirective> canonicalize(
            List<ProceduralDirective> source, ProceduralDiagnosticCollector diagnostics) {
        if (source.size() > ProceduralLimits.MAX_COMMANDS_PER_FRAME) {
            diagnostics.add(ProceduralDiagnosticSeverity.ERROR, ProceduralDiagnosticCode.COMMAND_OVERFLOW,
                    null, "procedural command budget exceeded after hook collection");
            return List.of();
        }
        List<ProceduralDirective> sorted = new ArrayList<>(source);
        sorted.sort(Comparator.comparingInt(ProceduralDirective::priority)
                .thenComparing(value -> value.sourceId().value())
                .thenComparing(value -> operationOrderKey(value.operation())));
        LinkedHashMap<String, ProceduralDirective> exclusive = new LinkedHashMap<>();
        List<ProceduralDirective> accepted = new ArrayList<>(sorted.size());
        Set<String> exact = new HashSet<>();
        for (ProceduralDirective directive : sorted) {
            if (!isValidTarget(directive.operation(), diagnostics, directive.sourceId())) {
                continue;
            }
            String semanticIdentity = directive.sourceId().value() + ':' + directive.priority() + ':'
                    + operationOrderKey(directive.operation());
            if (!exact.add(semanticIdentity)) {
                continue;
            }
            String exclusiveKey = exclusiveWriteKey(directive);
            if (exclusiveKey != null) {
                ProceduralDirective existing = exclusive.putIfAbsent(exclusiveKey, directive);
                if (existing != null && !existing.operation().equals(directive.operation())) {
                    diagnostics.add(ProceduralDiagnosticSeverity.ERROR, ProceduralDiagnosticCode.HOOK_CONFLICT,
                            directive.sourceId(), "same-priority incompatible procedural writes target one frozen property");
                    continue;
                }
            }
            accepted.add(directive);
        }
        return List.copyOf(accepted);
    }

    private boolean isValidTarget(
            ProceduralOperation operation, ProceduralDiagnosticCollector diagnostics, BlendResourceId sourceId) {
        try {
            switch (operation) {
                case ProceduralOperation.Offset offset -> plan.requireBoneIndex(offset.boneIndex());
                case ProceduralOperation.LookAt lookAt -> plan.requireBoneIndex(lookAt.boneIndex());
                case ProceduralOperation.RotationOffset rotation -> plan.requireBoneIndex(rotation.boneIndex());
                case ProceduralOperation.BoneVisibility visibility -> plan.requireBoneIndex(visibility.boneIndex());
                case ProceduralOperation.SurfaceVisibility visibility -> {
                    if (!plan.isKnownTarget(visibility.target())) {
                        throw new IllegalArgumentException("unknown frozen surface target");
                    }
                }
                case ProceduralOperation.SurfaceOverride override -> {
                    if (!plan.isKnownTarget(override.target())) {
                        throw new IllegalArgumentException("unknown frozen surface target");
                    }
                }
            }
            return true;
        } catch (RuntimeException exception) {
            diagnostics.add(ProceduralDiagnosticSeverity.ERROR, ProceduralDiagnosticCode.INVALID_TARGET,
                    sourceId, "procedural command target is absent from the frozen rig plan");
            return false;
        }
    }

    private static String exclusiveWriteKey(ProceduralDirective directive) {
        ProceduralOperation operation = directive.operation();
        String prefix = directive.priority() + ":";
        return switch (operation) {
            case ProceduralOperation.LookAt value -> prefix + "look:" + value.boneIndex();
            case ProceduralOperation.BoneVisibility value -> prefix + "bone-visible:" + value.boneIndex();
            case ProceduralOperation.SurfaceVisibility value -> prefix + "surface-visible:" + value.target();
            case ProceduralOperation.SurfaceOverride value -> prefix + "surface-override:" + value.target();
            default -> null;
        };
    }

    private static String operationOrderKey(ProceduralOperation operation) {
        return switch (operation) {
            case ProceduralOperation.Offset value -> "0:" + value.boneIndex() + ':'
                    + ProceduralSupport.canonicalFloatKey(value.translationOffset().x()) + ':'
                    + ProceduralSupport.canonicalFloatKey(value.translationOffset().y()) + ':'
                    + ProceduralSupport.canonicalFloatKey(value.translationOffset().z()) + ':'
                    + ProceduralSupport.canonicalQuaternionKey(value.rotationOffset()) + ':'
                    + ProceduralSupport.canonicalFloatKey(value.scaleMultiplier());
            case ProceduralOperation.LookAt value -> "1:" + value.boneIndex() + ':'
                    + ProceduralSupport.canonicalFloatKey(value.targetModelSpace().x()) + ':'
                    + ProceduralSupport.canonicalFloatKey(value.targetModelSpace().y()) + ':'
                    + ProceduralSupport.canonicalFloatKey(value.targetModelSpace().z()) + ':'
                    + ProceduralSupport.canonicalFloatKey(value.maximumYawRadians()) + ':'
                    + ProceduralSupport.canonicalFloatKey(value.maximumPitchRadians());
            case ProceduralOperation.RotationOffset value -> "2:" + value.boneIndex() + ':'
                    + ProceduralSupport.canonicalQuaternionKey(value.rotationOffset());
            case ProceduralOperation.BoneVisibility value -> "3:" + value.boneIndex() + ':' + value.visible();
            case ProceduralOperation.SurfaceVisibility value -> "4:" + value.target() + ':' + value.visible();
            case ProceduralOperation.SurfaceOverride value -> "5:" + value.target() + ':' + value.override().textureId() + ':'
                    + value.override().materialId();
        };
    }

    private Transform[] readPose(AnimationV2Pose pose) {
        Transform[] result = new Transform[pose.boneCount()];
        for (int index = 0; index < result.length; index++) {
            result[index] = pose.transform(index);
        }
        return result;
    }

    private static void applyOffsets(Transform[] local, List<ProceduralDirective> directives) {
        for (ProceduralDirective directive : directives) {
            if (directive.operation() instanceof ProceduralOperation.Offset offset) {
                local[offset.boneIndex()] = ProceduralSupport.composeOffset(local[offset.boneIndex()],
                        offset.translationOffset(), offset.rotationOffset(), offset.scaleMultiplier());
            }
        }
    }

    private void applyLookAt(
            Transform[] local, List<ProceduralDirective> directives, ProceduralDiagnosticCollector diagnostics) {
        for (ProceduralDirective directive : directives) {
            if (!(directive.operation() instanceof ProceduralOperation.LookAt lookAt)) {
                continue;
            }
            List<Transform> modelTransforms = modelTransforms(local);
            int bone = lookAt.boneIndex();
            Transform parent = plan.parentOf(bone) == -1 ? Transform.IDENTITY : modelTransforms.get(plan.parentOf(bone));
            Transform currentModel = modelTransforms.get(bone);
            Vec3 targetDelta = lookAt.targetModelSpace().subtract(currentModel.translation());
            float length = targetDelta.length();
            if (!Float.isFinite(length) || length <= ProceduralLimits.DIRECTION_EPSILON) {
                diagnostics.add(ProceduralDiagnosticSeverity.WARN, ProceduralDiagnosticCode.INVALID_TARGET,
                        directive.sourceId(), "look-at target is degenerate; existing orientation was preserved");
                continue;
            }
            Vec3 parentDirection = ProceduralSupport.inverseRotate(parent.rotation(), targetDelta).normalized();
            Transform current = local[bone];
            Vec3 currentForward = current.rotation().rotate(new Vec3(0.0F, 0.0F, 1.0F));
            float currentYaw = (float) Math.atan2(currentForward.x(), currentForward.z());
            float currentPitch = (float) Math.atan2(-currentForward.y(),
                    Math.sqrt(currentForward.x() * currentForward.x() + currentForward.z() * currentForward.z()));
            float targetYaw = (float) Math.atan2(parentDirection.x(), parentDirection.z());
            float targetPitch = (float) Math.atan2(-parentDirection.y(),
                    Math.sqrt(parentDirection.x() * parentDirection.x() + parentDirection.z() * parentDirection.z()));
            float yaw = currentYaw + ProceduralSupport.clamp(
                    ProceduralSupport.shortestAngle(targetYaw - currentYaw),
                    -lookAt.maximumYawRadians(), lookAt.maximumYawRadians());
            float pitch = currentPitch + ProceduralSupport.clamp(targetPitch - currentPitch,
                    -lookAt.maximumPitchRadians(), lookAt.maximumPitchRadians());
            local[bone] = new Transform(current.translation(), ProceduralSupport.yawPitch(yaw, pitch), current.scale());
        }
    }

    private static void applyRotationOffsets(Transform[] local, List<ProceduralDirective> directives) {
        for (ProceduralDirective directive : directives) {
            if (directive.operation() instanceof ProceduralOperation.RotationOffset rotation) {
                Transform current = local[rotation.boneIndex()];
                local[rotation.boneIndex()] = new Transform(current.translation(),
                        ProceduralSupport.canonicalQuaternion(ProceduralSupport.canonicalQuaternion(rotation.rotationOffset())
                                .multiply(ProceduralSupport.canonicalQuaternion(current.rotation()))), current.scale());
            }
        }
    }

    private List<Transform> modelTransforms(Transform[] local) {
        Transform[] resolved = new Transform[local.length];
        byte[] states = new byte[local.length];
        for (int index = 0; index < local.length; index++) {
            resolveModelTransform(index, local, resolved, states);
        }
        return List.copyOf(Arrays.asList(resolved));
    }

    private Transform resolveModelTransform(int index, Transform[] local, Transform[] resolved, byte[] states) {
        if (states[index] == 2) {
            return resolved[index];
        }
        if (states[index] == 1) {
            throw new IllegalStateException("frozen parent hierarchy unexpectedly contains a cycle");
        }
        states[index] = 1;
        int parent = plan.parentOf(index);
        resolved[index] = parent == -1
                ? local[index]
                : resolveModelTransform(parent, local, resolved, states).compose(local[index]);
        states[index] = 2;
        return resolved[index];
    }

    private boolean[] resolveBoneVisibility(List<ProceduralDirective> directives) {
        boolean[] visible = new boolean[plan.schema().boneCount()];
        Arrays.fill(visible, true);
        for (ProceduralDirective directive : directives) {
            if (directive.operation() instanceof ProceduralOperation.BoneVisibility visibility) {
                visible[visibility.boneIndex()] = visibility.visible();
            }
        }
        return visible;
    }

    private void propagateParentVisibility(boolean[] visibility) {
        boolean[] resolved = new boolean[visibility.length];
        byte[] states = new byte[visibility.length];
        for (int index = 0; index < visibility.length; index++) {
            visibility[index] = resolveVisibility(index, visibility, resolved, states);
        }
    }

    private boolean resolveVisibility(int index, boolean[] local, boolean[] resolved, byte[] states) {
        if (states[index] == 2) {
            return resolved[index];
        }
        if (states[index] == 1) {
            throw new IllegalStateException("frozen parent hierarchy unexpectedly contains a cycle");
        }
        states[index] = 1;
        int parent = plan.parentOf(index);
        resolved[index] = local[index] && (parent == -1 || resolveVisibility(parent, local, resolved, states));
        states[index] = 2;
        return resolved[index];
    }

    private Map<ProceduralSurfaceTarget, Boolean> resolveSurfaceVisibility(
            List<ProceduralDirective> directives, boolean[] boneVisibility) {
        LinkedHashMap<ProceduralSurfaceTarget, Boolean> values = new LinkedHashMap<>(plan.initialSurfaceVisibility());
        for (ProceduralDirective directive : directives) {
            if (directive.operation() instanceof ProceduralOperation.SurfaceVisibility visibility) {
                values.put(visibility.target(), visibility.visible());
            }
        }
        for (Map.Entry<ProceduralSurfaceTarget, Boolean> entry : values.entrySet()) {
            int owner = plan.owningBone(entry.getKey());
            if (owner >= 0 && !boneVisibility[owner]) {
                entry.setValue(Boolean.FALSE);
            }
        }
        return values;
    }

    private static Map<ProceduralSurfaceTarget, SemanticSurfaceOverride> resolveSurfaceOverrides(
            List<ProceduralDirective> directives) {
        LinkedHashMap<ProceduralSurfaceTarget, SemanticSurfaceOverride> values = new LinkedHashMap<>();
        for (ProceduralDirective directive : directives) {
            if (directive.operation() instanceof ProceduralOperation.SurfaceOverride override) {
                values.put(override.target(), override.override());
            }
        }
        return values;
    }

    private SocketState resolveSockets(List<Transform> modelTransforms, boolean[] visibility) {
        LinkedHashMap<BlendResourceId, ProceduralSocketTransform> output = new LinkedHashMap<>();
        LinkedHashSet<BlendResourceId> hidden = new LinkedHashSet<>();
        for (ProceduralSocketDefinition socket : plan.sockets()) {
            if (!visibility[socket.boneIndex()]) {
                hidden.add(socket.id());
                continue;
            }
            output.put(socket.id(), new ProceduralSocketTransform(socket.boneIndex(),
                    modelTransforms.get(socket.boneIndex()).compose(socket.localTransform())));
        }
        LinkedHashMap<BlendResourceId, Transform> transforms = new LinkedHashMap<>();
        for (Map.Entry<BlendResourceId, ProceduralSocketTransform> entry : output.entrySet()) {
            transforms.put(entry.getKey(), entry.getValue().transform());
        }
        return new SocketState(output, transforms, hidden);
    }

    private List<ProceduralResolvedAttachment> resolveAttachments(
            ProceduralFrameInput input,
            List<Transform> modelTransforms,
            boolean[] visibility,
            SocketState sockets,
            ProceduralDiagnosticCollector diagnostics) {
        List<ProceduralAttachmentDescriptor> descriptors = new ArrayList<>(plan.attachments());
        descriptors.addAll(input.attachments());
        if (descriptors.size() > ProceduralLimits.MAX_ATTACHMENTS) {
            diagnostics.add(ProceduralDiagnosticSeverity.ERROR, ProceduralDiagnosticCode.ATTACHMENT_LIMIT,
                    null, "combined attachment count exceeds frozen X3 limit");
            return List.of();
        }
        descriptors.sort(Comparator.comparing(value -> value.id().value()));
        Set<BlendResourceId> ids = new HashSet<>();
        List<ProceduralResolvedAttachment> resolved = new ArrayList<>();
        for (ProceduralAttachmentDescriptor descriptor : descriptors) {
            if (!ids.add(descriptor.id())) {
                diagnostics.add(ProceduralDiagnosticSeverity.ERROR, ProceduralDiagnosticCode.DUPLICATE_ID,
                        descriptor.id(), "attachment id is duplicated in one frozen frame");
                continue;
            }
            Transform anchor = null;
            if (descriptor.anchor() instanceof ProceduralAttachmentAnchor.Bone bone) {
                if (bone.boneIndex() >= visibility.length) {
                    diagnostics.add(ProceduralDiagnosticSeverity.WARN, ProceduralDiagnosticCode.ATTACHMENT_TARGET_MISSING,
                            descriptor.id(), "attachment bone target is absent from this frozen frame");
                } else if (!visibility[bone.boneIndex()]) {
                    diagnostics.add(ProceduralDiagnosticSeverity.INFO, ProceduralDiagnosticCode.ATTACHMENT_HIDDEN_TARGET,
                            descriptor.id(), "attachment target is hidden in this frozen frame");
                } else {
                    anchor = modelTransforms.get(bone.boneIndex());
                }
            } else if (descriptor.anchor() instanceof ProceduralAttachmentAnchor.Socket socket) {
                anchor = sockets.transforms().get(socket.socketId());
                if (anchor == null) {
                    diagnostics.add(ProceduralDiagnosticSeverity.WARN,
                            sockets.hiddenIds().contains(socket.socketId())
                                    ? ProceduralDiagnosticCode.ATTACHMENT_HIDDEN_TARGET
                                    : ProceduralDiagnosticCode.ATTACHMENT_TARGET_MISSING,
                            descriptor.id(), "attachment socket target is absent or hidden in this frozen frame");
                }
            }
            if (anchor != null) {
                resolved.add(new ProceduralResolvedAttachment(descriptor, anchor.compose(descriptor.localTransform())));
            }
        }
        return List.copyOf(resolved);
    }

    private static List<Boolean> toBooleanList(boolean[] values) {
        List<Boolean> copied = new ArrayList<>(values.length);
        for (boolean value : values) {
            copied.add(value);
        }
        return List.copyOf(copied);
    }

    private static ProceduralEvaluationResult failed(
            ProceduralFrameSnapshot prior, ProceduralDiagnosticCollector diagnostics) {
        return new ProceduralEvaluationResult(false, prior, diagnostics.snapshot());
    }

    private final class BoundedHookSink implements ProceduralHookCommandSink {
        private final BlendResourceId hookId;
        private final int priority;
        private final List<ProceduralDirective> directives;
        private final ProceduralDiagnosticCollector diagnostics;

        private BoundedHookSink(
                BlendResourceId hookId,
                int priority,
                List<ProceduralDirective> directives,
                ProceduralDiagnosticCollector diagnostics) {
            this.hookId = hookId;
            this.priority = priority;
            this.directives = directives;
            this.diagnostics = diagnostics;
        }

        @Override
        public void emit(ProceduralOperation operation) {
            if (directives.size() >= ProceduralLimits.MAX_COMMANDS_PER_FRAME) {
                diagnostics.add(ProceduralDiagnosticSeverity.ERROR, ProceduralDiagnosticCode.COMMAND_OVERFLOW,
                        hookId, "hook command budget exceeded");
                return;
            }
            directives.add(new ProceduralDirective(hookId, priority, Objects.requireNonNull(operation, "operation")));
        }

        @Override
        public void emitVisualEvent(ProceduralVisualEvent event) {
            Objects.requireNonNull(event, "event");
            diagnostics.add(ProceduralDiagnosticSeverity.ERROR, ProceduralDiagnosticCode.VISUAL_EVENT_PROVENANCE,
                    hookId, "hook-provided visual events are untrusted; only frozen timeline markers may publish events");
        }
    }

    private record SocketState(
            Map<BlendResourceId, ProceduralSocketTransform> socketTransforms,
            Map<BlendResourceId, Transform> transforms,
            Set<BlendResourceId> hiddenIds) {
    }
}
