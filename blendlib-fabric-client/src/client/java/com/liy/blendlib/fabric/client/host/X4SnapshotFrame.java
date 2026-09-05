package com.liy.blendlib.fabric.client.host;

import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import java.util.Objects;
import java.util.Optional;

/**
 * Common extraction input for every X4 host.
 *
 * <p>A caller may supply an already extracted snapshot for a skinned path. Otherwise the X4
 * adapter resolves a prepared handle through {@code ClientModelLookup} before submit. Neither
 * choice permits lookup, parsing, or resource work during submit.</p>
 */
public record X4SnapshotFrame(
        X4HostIdentity identity,
        X4Transform transform,
        int packedLight,
        int packedOverlay,
        int tintArgb,
        boolean visible,
        Optional<ModelRenderSnapshot> extractedSnapshot,
        Optional<X4MissingModelDiagnostic> capturedMissingDiagnostic) {
    public X4SnapshotFrame {
        identity = Objects.requireNonNull(identity, "identity");
        transform = Objects.requireNonNull(transform, "transform");
        extractedSnapshot = Objects.requireNonNull(extractedSnapshot, "extractedSnapshot");
        capturedMissingDiagnostic = Objects.requireNonNull(capturedMissingDiagnostic, "capturedMissingDiagnostic");
        if (extractedSnapshot.isEmpty() && capturedMissingDiagnostic.isPresent()) {
            throw new IllegalArgumentException("Only an extracted missing snapshot may carry missing-model evidence");
        }
        if (extractedSnapshot.isPresent()) {
            ModelRenderSnapshot snapshot = extractedSnapshot.orElseThrow();
            if (snapshot.handle().missingModel()) {
                X4MissingModelDiagnostic diagnostic = capturedMissingDiagnostic.orElseThrow(() ->
                        new IllegalArgumentException("An extracted missing snapshot requires structured diagnostic evidence"));
                diagnostic.requireFor(snapshot.handle().modelKey(), snapshot.generation());
            } else if (capturedMissingDiagnostic.isPresent()) {
                throw new IllegalArgumentException("A non-missing extracted snapshot cannot carry missing-model evidence");
            }
        }
    }

    /** Creates a normal rest-pose extraction input that will resolve its handle before submit. */
    public static X4SnapshotFrame unresolved(
            X4HostIdentity identity,
            X4Transform transform,
            int packedLight,
            int packedOverlay,
            int tintArgb,
            boolean visible) {
        return new X4SnapshotFrame(
                identity, transform, packedLight, packedOverlay, tintArgb, visible, Optional.empty(), Optional.empty());
    }

    /** Creates an input carrying a snapshot previously extracted by an existing BlendLib seam. */
    public static X4SnapshotFrame extracted(
            X4HostIdentity identity,
            X4Transform transform,
            int packedLight,
            int packedOverlay,
            int tintArgb,
            boolean visible,
            ModelRenderSnapshot snapshot) {
        ModelRenderSnapshot checked = Objects.requireNonNull(snapshot, "snapshot");
        if (checked.handle().missingModel()) {
            throw new IllegalArgumentException("Use extractedMissing for a missing captured snapshot");
        }
        return new X4SnapshotFrame(
                identity,
                transform,
                packedLight,
                packedOverlay,
                tintArgb,
                visible,
                Optional.of(checked),
                Optional.empty());
    }

    /** Creates an already-extracted missing snapshot with exact model/generation evidence. */
    public static X4SnapshotFrame extractedMissing(
            X4HostIdentity identity,
            X4Transform transform,
            int packedLight,
            int packedOverlay,
            int tintArgb,
            boolean visible,
            ModelRenderSnapshot snapshot,
            X4MissingModelDiagnostic diagnostic) {
        ModelRenderSnapshot checked = Objects.requireNonNull(snapshot, "snapshot");
        if (!checked.handle().missingModel()) {
            throw new IllegalArgumentException("Only a missing snapshot may use extractedMissing");
        }
        return new X4SnapshotFrame(
                identity,
                transform,
                packedLight,
                packedOverlay,
                tintArgb,
                visible,
                Optional.of(checked),
                Optional.of(Objects.requireNonNull(diagnostic, "diagnostic")));
    }
}
