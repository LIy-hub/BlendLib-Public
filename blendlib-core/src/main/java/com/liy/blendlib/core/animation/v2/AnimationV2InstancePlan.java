package com.liy.blendlib.core.animation.v2;

import com.liy.blendlib.api.BlendResourceId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable v2 configuration for one model instance shape, frozen before runtime use. */
public final class AnimationV2InstancePlan {
    private final BoneSchema boneSchema;
    private final List<AnimationV2ControllerDefinition> controllers;
    private final Map<BlendResourceId, AnimationV2ControllerDefinition> controllersById;
    private final List<AnimationV2Diagnostic> staticDiagnostics;

    public AnimationV2InstancePlan(BoneSchema boneSchema, List<AnimationV2ControllerDefinition> controllers) {
        this.boneSchema = Objects.requireNonNull(boneSchema, "boneSchema");
        Objects.requireNonNull(controllers, "controllers");
        if (controllers.isEmpty() || controllers.size() > AnimationV2Limits.MAX_CONTROLLERS_PER_INSTANCE) {
            throw new IllegalArgumentException("controller count is outside supported bounds");
        }
        LinkedHashMap<BlendResourceId, AnimationV2ControllerDefinition> copied = new LinkedHashMap<>();
        for (AnimationV2ControllerDefinition controller : controllers) {
            AnimationV2ControllerDefinition checked = Objects.requireNonNull(controller, "controller");
            if (copied.putIfAbsent(checked.id(), checked) != null) {
                throw new IllegalArgumentException("duplicate canonical controller id: " + checked.id());
            }
            for (AnimationV2LayerDefinition layer : checked.layers()) {
                if (layer.mask().boneCount() != boneSchema.boneCount()) {
                    throw new IllegalArgumentException("controller layer mask does not match the instance bone schema");
                }
            }
        }
        List<AnimationV2ControllerDefinition> ordered = new ArrayList<>(copied.values());
        ordered.sort(Comparator.comparingInt(AnimationV2ControllerDefinition::priority)
                .thenComparing(controller -> controller.id().value()));
        this.controllers = List.copyOf(ordered);
        this.controllersById = Collections.unmodifiableMap(copied);
        List<AnimationV2Diagnostic> diagnostics = new ArrayList<>();
        for (AnimationV2ControllerDefinition controller : ordered) {
            diagnostics.addAll(controller.staticDiagnostics());
        }
        this.staticDiagnostics = List.copyOf(diagnostics);
    }

    public BoneSchema boneSchema() {
        return boneSchema;
    }

    /** Deterministic composition order: priority ascending then canonical controller id ascending. */
    public List<AnimationV2ControllerDefinition> controllers() {
        return controllers;
    }

    public AnimationV2ControllerDefinition controller(BlendResourceId id) {
        AnimationV2ControllerDefinition controller = controllersById.get(Objects.requireNonNull(id, "id"));
        if (controller == null) {
            throw new IllegalArgumentException("undeclared v2 controller: " + id);
        }
        return controller;
    }

    AnimationV2ControllerDefinition findController(BlendResourceId id) {
        return controllersById.get(Objects.requireNonNull(id, "id"));
    }

    public List<AnimationV2Diagnostic> staticDiagnostics() {
        return staticDiagnostics;
    }

    /** Frozen controller whitelist for semantic ingress validation before any instance retention occurs. */
    public Set<BlendResourceId> controllerIds() {
        return controllersById.keySet();
    }
}
