package com.liy.blendlib.showcase.perf.x7;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Conservative structural diagnostic for two independently retained X7 capture packages.
 *
 * <p>This tooling package has no trusted capture owner/capability, so it cannot emit a hardware
 * comparison result or numeric deltas. It only reports whether retained structural tokens are
 * current enough to remain waiting for that future owner integration.</p>
 */
public final class X7BenchmarkComparator {
    private X7BenchmarkComparator() {
    }

    /**
     * Returns structural diagnostics only. Current attested packages remain waiting for a future
     * trusted capture owner; invalid or stale packages are not comparable.
     */
    public static ComparisonResult compare(
            X7BenchmarkEvidence left,
            Path leftArtifactRoot,
            X7BenchmarkEvidenceValidator.ValidationResult leftValidation,
            X7BenchmarkEvidence right,
            Path rightArtifactRoot,
            X7BenchmarkEvidenceValidator.ValidationResult rightValidation) {
        X7BenchmarkEvidence checkedLeft = Objects.requireNonNull(left, "left");
        Path checkedLeftRoot = Objects.requireNonNull(leftArtifactRoot, "leftArtifactRoot");
        X7BenchmarkEvidence checkedRight = Objects.requireNonNull(right, "right");
        Path checkedRightRoot = Objects.requireNonNull(rightArtifactRoot, "rightArtifactRoot");
        X7BenchmarkEvidenceValidator.ValidationResult checkedLeftValidation =
                Objects.requireNonNull(leftValidation, "leftValidation");
        X7BenchmarkEvidenceValidator.ValidationResult checkedRightValidation =
                Objects.requireNonNull(rightValidation, "rightValidation");

        List<String> reasons = new ArrayList<>();
        boolean leftAttests = checkedLeftValidation.attests(checkedLeft, checkedLeftRoot);
        boolean rightAttests = checkedRightValidation.attests(checkedRight, checkedRightRoot);
        if (!leftAttests) {
            reasons.add("left validation token is not bound to the current evidence/root/manifest inventory");
        }
        if (!rightAttests) {
            reasons.add("right validation token is not bound to the current evidence/root/manifest inventory");
        }
        if (!checkedLeft.scenario().equals(checkedRight.scenario())) {
            reasons.add("frozen scenario differs");
        }
        if (checkedLeft.backend().backendClass() != checkedRight.backend().backendClass()) {
            reasons.add("backend class differs");
        }
        compareField("backend name", checkedLeft.backend().backendName(), checkedRight.backend().backendName(), reasons);
        compareField("backend capability", checkedLeft.backend().capability(), checkedRight.backend().capability(), reasons);
        if (checkedLeft.backend().fallbackState() != checkedRight.backend().fallbackState()) {
            reasons.add("fallback state differs");
        }
        appendEnvironmentDifferences(checkedLeft.environment(), checkedRight.environment(), reasons);
        if (!leftAttests || !rightAttests
                || checkedLeftValidation.status() == X7BenchmarkEvidenceValidator.Status.INVALID
                || checkedRightValidation.status() == X7BenchmarkEvidenceValidator.Status.INVALID) {
            return new ComparisonResult(
                    Status.NOT_COMPARABLE,
                    List.copyOf(reasons),
                    Conclusion.NO_AUTOMATIC_GPU_SPEED_CLAIM);
        }
        reasons.add("no trusted capture owner/capability is implemented; numeric hardware comparison remains WAITING");
        return new ComparisonResult(
                Status.WAITING_FOR_TRUSTED_OWNER,
                List.copyOf(reasons),
                Conclusion.NO_AUTOMATIC_GPU_SPEED_CLAIM);
    }

    private static void appendEnvironmentDifferences(
            X7BenchmarkEvidence.RuntimeEnvironment left,
            X7BenchmarkEvidence.RuntimeEnvironment right,
            List<String> reasons) {
        compareField("Java version", left.javaVersion(), right.javaVersion(), reasons);
        compareField("OS name", left.osName(), right.osName(), reasons);
        compareField("OS version", left.osVersion(), right.osVersion(), reasons);
        compareField("Minecraft version", left.minecraftVersion(), right.minecraftVersion(), reasons);
        compareField("Fabric Loader version", left.fabricLoaderVersion(), right.fabricLoaderVersion(), reasons);
        compareField("Fabric API version", left.fabricApiVersion(), right.fabricApiVersion(), reasons);
        compareField("GPU name", left.gpuName(), right.gpuName(), reasons);
        compareField("GPU vendor", left.gpuVendor(), right.gpuVendor(), reasons);
        compareField("GPU driver", left.gpuDriver(), right.gpuDriver(), reasons);
        compareField("shaderpack state", left.shaderpack(), right.shaderpack(), reasons);
        compareField("Iris state", left.irisStatus(), right.irisStatus(), reasons);
        compareField("Sodium state", left.sodiumStatus(), right.sodiumStatus(), reasons);
    }

    private static void compareField(String name, String left, String right, List<String> reasons) {
        if (!Objects.equals(left, right)) {
            reasons.add(name + " differs");
        }
    }

    public enum Status {
        NOT_COMPARABLE,
        WAITING_FOR_TRUSTED_OWNER
    }

    /** Intentionally the only conclusion; tool output never equates deltas with a speed claim. */
    public enum Conclusion {
        NO_AUTOMATIC_GPU_SPEED_CLAIM
    }

    /** Structural diagnostic output; its type system has no hardware-comparable state. */
    public static final class ComparisonResult {
        private final Status status;
        private final List<String> reasons;
        private final Conclusion conclusion;

        private ComparisonResult(Status status, List<String> reasons, Conclusion conclusion) {
            this.status = Objects.requireNonNull(status, "status");
            this.reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
            this.conclusion = Objects.requireNonNull(conclusion, "conclusion");
        }

        public Status status() {
            return status;
        }

        public List<String> reasons() {
            return reasons;
        }

        public Conclusion conclusion() {
            return conclusion;
        }
    }
}
