package com.liy.blendlib.datagen;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Mutable request builder for deterministic pure-Java BlendLib data generation.
 *
 * <p><strong>Stable boundary:</strong> mutation is restricted to construction time. Calling
 * {@link #generate()} validates the complete request before any file is created, then delegates to
 * per-file UTF-8/LF atomic publication. No platform loader or runtime renderer is involved.</p>
 */
public final class BlendLibDatagenBuilder {
    private Path outputRoot;
    private Integer packFormat;
    private String packDescription;
    private final List<BlendDescriptorSpec> descriptors = new ArrayList<>();

    /**
     * Sets the sole approved root for every generated output path.
     *
     * @param outputRoot destination pack root; normalized containment is verified before writing
     * @return this builder
     */
    public BlendLibDatagenBuilder outputRoot(Path outputRoot) {
        this.outputRoot = Objects.requireNonNull(outputRoot, "outputRoot");
        return this;
    }

    /**
     * Sets the target pack-format integer written to the generated pack skeleton.
     *
     * @param packFormat non-negative pack-format value chosen by the integration owner
     * @return this builder
     */
    public BlendLibDatagenBuilder packFormat(int packFormat) {
        if (packFormat < 0) {
            throw new IllegalArgumentException("packFormat must be non-negative");
        }
        this.packFormat = packFormat;
        return this;
    }

    /**
     * Sets the plain pack description emitted in {@code pack.mcmeta}.
     *
     * @param packDescription non-blank descriptive text
     * @return this builder
     */
    public BlendLibDatagenBuilder packDescription(String packDescription) {
        String checked = Objects.requireNonNull(packDescription, "packDescription");
        if (checked.isBlank() || checked.length() > 256) {
            throw new IllegalArgumentException("packDescription must be non-blank and at most 256 characters");
        }
        this.packDescription = checked;
        return this;
    }

    /**
     * Adds one complete strict descriptor/sidecar specification.
     *
     * @param descriptor immutable strict authoring specification
     * @return this builder
     */
    public BlendLibDatagenBuilder addDescriptor(BlendDescriptorSpec descriptor) {
        if (descriptors.size() >= DatagenLimits.MAX_DESCRIPTOR_SPECS) {
            throw new DatagenException(DatagenDiagnosticCode.OUTPUT_LIMIT_EXCEEDED,
                    "datagen request exceeds " + DatagenLimits.MAX_DESCRIPTOR_SPECS + " descriptor specifications");
        }
        descriptors.add(Objects.requireNonNull(descriptor, "descriptor"));
        return this;
    }

    /**
     * Validates the request and atomically writes deterministic UTF-8/LF artifacts.
     *
     * @return immutable generated file manifest
     * @throws DatagenException for invalid scope, duplicate paths, or output failures
     */
    public DatagenOutput generate() {
        if (outputRoot == null || packFormat == null || packDescription == null || descriptors.isEmpty()) {
            throw new DatagenException(DatagenDiagnosticCode.INVALID_SPECIFICATION,
                    "outputRoot, packFormat, packDescription, and at least one descriptor are required");
        }
        return new DatagenGenerator(outputRoot, packFormat, packDescription, List.copyOf(descriptors)).generate();
    }
}
