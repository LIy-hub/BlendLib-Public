package com.liy.blendlib.core.tooling;

import com.liy.blendlib.api.BlendResourceId;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Caller-owned filesystem inputs for one local X5 artifact validation run. */
public record AssetValidationRequest(
        Path projectRoot,
        String resourceRoot,
        BlendResourceId modelKey,
        String reportPath,
        String sidecarPath,
        List<String> runtimeRoots) {
    private static final int MAX_RUNTIME_ROOTS = 16;

    public AssetValidationRequest(
            Path projectRoot,
            String resourceRoot,
            BlendResourceId modelKey,
            String reportPath,
            String sidecarPath) {
        this(projectRoot, resourceRoot, modelKey, reportPath, sidecarPath, defaultRuntimeRoots(resourceRoot));
    }

    public AssetValidationRequest {
        projectRoot = Objects.requireNonNull(projectRoot, "projectRoot");
        resourceRoot = requireText(resourceRoot, "resourceRoot");
        modelKey = Objects.requireNonNull(modelKey, "modelKey");
        reportPath = requireText(reportPath, "reportPath");
        sidecarPath = requireText(sidecarPath, "sidecarPath");
        runtimeRoots = mergedRuntimeRoots(resourceRoot, Objects.requireNonNull(runtimeRoots, "runtimeRoots"));
        if (runtimeRoots.size() > MAX_RUNTIME_ROOTS) {
            throw new IllegalArgumentException("runtimeRoots must contain 1-16 entries");
        }
    }

    private static List<String> defaultRuntimeRoots(String resourceRoot) {
        LinkedHashSet<String> roots = new LinkedHashSet<>();
        roots.add(requireText(resourceRoot, "resourceRoot"));
        roots.add("src/main/resources");
        roots.add("build/resources/main");
        return List.copyOf(roots);
    }

    private static List<String> mergedRuntimeRoots(String resourceRoot, List<String> explicitRoots) {
        LinkedHashSet<String> roots = new LinkedHashSet<>(defaultRuntimeRoots(resourceRoot));
        explicitRoots.forEach(root -> roots.add(requireText(root, "runtimeRoot")));
        if (roots.size() > MAX_RUNTIME_ROOTS) {
            throw new IllegalArgumentException("runtimeRoots must contain 1-16 entries after default-root union");
        }
        return List.copyOf(roots);
    }

    private static String requireText(String value, String name) {
        value = Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
