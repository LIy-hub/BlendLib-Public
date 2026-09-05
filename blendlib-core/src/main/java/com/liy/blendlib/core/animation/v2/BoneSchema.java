package com.liy.blendlib.core.animation.v2;

import com.liy.blendlib.core.model.Transform;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable ordered bone domain prepared before v2 playback; name lookup is configuration-only. */
public final class BoneSchema {
    private final List<String> names;
    private final Map<String, Integer> indicesByName;
    private final AnimationV2Pose restPose;

    public BoneSchema(List<String> names, List<Transform> restTransforms) {
        Objects.requireNonNull(names, "names");
        Objects.requireNonNull(restTransforms, "restTransforms");
        if (names.isEmpty() || names.size() > AnimationV2Limits.MAX_BONES_PER_SCHEMA) {
            throw new IllegalArgumentException("bone count must be in [1, " + AnimationV2Limits.MAX_BONES_PER_SCHEMA + "]");
        }
        if (names.size() != restTransforms.size()) {
            throw new IllegalArgumentException("bone names and rest transforms must have equal cardinality");
        }
        List<String> copiedNames = new ArrayList<>(names.size());
        LinkedHashMap<String, Integer> copiedIndices = new LinkedHashMap<>();
        for (int index = 0; index < names.size(); index++) {
            String name = Objects.requireNonNull(names.get(index), "boneName");
            if (name.isBlank() || name.length() > AnimationV2Limits.MAX_IDENTIFIER_UTF16_CODE_UNITS) {
                throw new IllegalArgumentException("bone name must be non-blank and bounded: " + name);
            }
            if (copiedIndices.putIfAbsent(name, index) != null) {
                throw new IllegalArgumentException("duplicate bone name: " + name);
            }
            copiedNames.add(name);
        }
        this.names = List.copyOf(copiedNames);
        this.indicesByName = Collections.unmodifiableMap(copiedIndices);
        this.restPose = new AnimationV2Pose(restTransforms);
    }

    public int boneCount() {
        return names.size();
    }

    public List<String> names() {
        return names;
    }

    /** Configuration-time conversion from a declared name to an immutable numeric slot. */
    public int requireIndex(String name) {
        Integer value = indicesByName.get(Objects.requireNonNull(name, "name"));
        if (value == null) {
            throw new IllegalArgumentException("unknown bone name: " + name);
        }
        return value;
    }

    public String nameAt(int index) {
        return names.get(requireIndexInRange(index));
    }

    public AnimationV2Pose restPose() {
        return restPose;
    }

    int requireIndexInRange(int index) {
        if (index < 0 || index >= names.size()) {
            throw new IllegalArgumentException("bone index is outside schema bounds: " + index);
        }
        return index;
    }
}
