package com.liy.blendlib.core.profile.experimental;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.asset.AssetBytes;
import com.liy.blendlib.core.diagnostic.BlendAssetLoadException;
import com.liy.blendlib.core.glb.GlbDocument;
import com.liy.blendlib.core.glb.GlbReader;
import java.util.LinkedHashSet;
import java.util.Objects;

/**
 * Entry point for strict X9 candidate validation.
 *
 * <p>The result is intentionally validation-only: it does not create a v1
 * {@code ModelAsset}, select a renderer, register a provider, or expose an API
 * surface. Integration is experimental and may change without compatibility guarantees.</p>
 */
public final class ExperimentalProfileValidator {
    private final ExperimentalProfileLimits limits;
    private final ExperimentalDescriptorDecoder descriptorDecoder;
    private final GlbReader glbReader;
    private final ExperimentalGlbProfileValidator glbValidator;

    public ExperimentalProfileValidator() {
        this(ExperimentalProfileLimits.DEFAULT);
    }

    public ExperimentalProfileValidator(ExperimentalProfileLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.descriptorDecoder = new ExperimentalDescriptorDecoder(limits);
        this.glbReader = new GlbReader(limits.baseGlbLimits());
        this.glbValidator = new ExperimentalGlbProfileValidator(limits);
    }

    /** Validates an already-resolved descriptor/GLB pair without file-system or Minecraft dependencies. */
    public ExperimentalProfileValidationResult validate(
            BlendResourceId modelKey, AssetBytes descriptorBytes, AssetBytes glbBytes) {
        Objects.requireNonNull(modelKey, "modelKey");
        ExperimentalDescriptor descriptor = descriptorDecoder.decode(modelKey, descriptorBytes);
        if (!descriptor.meshId().equals(Objects.requireNonNull(glbBytes, "glbBytes").resourceId())) {
            throw new ExperimentalProfileValidationException(new ExperimentalProfileDiagnostic(
                    ExperimentalProfileDiagnostic.Severity.ERROR, "BLENDLIB-X9-DESC-002", "/mesh",
                    "GLB bytes must have the exact descriptor mesh resource identity",
                    OptionalCapabilityFallback.MISSING_MODEL.serializedName()));
        }
        ExperimentalCapabilityNegotiator.Result negotiation = ExperimentalCapabilityNegotiator.negotiate(descriptor);
        GlbDocument document;
        try {
            document = glbReader.read(modelKey, glbBytes);
        } catch (RuntimeException exception) {
            if (exception instanceof ExperimentalProfileValidationException validationException) {
                throw validationException;
            }
            if (exception instanceof BlendAssetLoadException loadException) {
                throw new ExperimentalProfileValidationException(new ExperimentalProfileDiagnostic(
                        ExperimentalProfileDiagnostic.Severity.ERROR, loadException.diagnostic().code(),
                        loadException.diagnostic().location(), loadException.diagnostic().message(),
                        OptionalCapabilityFallback.MISSING_MODEL.serializedName()));
            }
            throw new ExperimentalProfileValidationException(new ExperimentalProfileDiagnostic(
                    ExperimentalProfileDiagnostic.Severity.ERROR, "BLENDLIB-X9-GLB-002", "",
                    "GLB container is invalid for the X9 candidate", OptionalCapabilityFallback.MISSING_MODEL.serializedName()));
        }
        ExperimentalGlbProfileValidator.Result result;
        try {
            ExperimentalGlbStructureValidator.Result structure = new ExperimentalGlbStructureValidator(
                    modelKey, glbBytes.resourceId(), document, limits).validate();
            result = glbValidator.validate(descriptor, document.json(), structure,
                    new LinkedHashSet<>(negotiation.selected()));
        } catch (BlendAssetLoadException exception) {
            throw new ExperimentalProfileValidationException(new ExperimentalProfileDiagnostic(
                    ExperimentalProfileDiagnostic.Severity.ERROR, exception.diagnostic().code(), exception.diagnostic().location(),
                    exception.diagnostic().message(), OptionalCapabilityFallback.MISSING_MODEL.serializedName()));
        }
        return new ExperimentalProfileValidationResult(modelKey, descriptor, result.primitiveCount(), result.morphTargetCount(),
                result.cubicSplineSamplerCount(), result.vertexColorPrimitiveCount(), result.secondaryUvPrimitiveCount(),
                negotiation.selected(), negotiation.diagnostics());
    }
}
