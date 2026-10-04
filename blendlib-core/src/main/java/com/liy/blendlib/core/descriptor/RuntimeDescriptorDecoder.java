package com.liy.blendlib.core.descriptor;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.asset.AssetBytes;
import com.liy.blendlib.core.diagnostic.BlendAssetLoadException;
import com.liy.blendlib.core.json.JsonNumber;
import com.liy.blendlib.core.json.JsonObject;
import com.liy.blendlib.core.json.JsonString;
import com.liy.blendlib.core.model.ModelProfile;
import com.liy.blendlib.core.json.StrictJsonParser;
import com.liy.blendlib.core.limits.BlendAssetLimits;
import java.util.Objects;

/** Bounded explicit version dispatch. Experimental X9 profiles are never runtime profiles. */
public final class RuntimeDescriptorDecoder {
    private final BlendAssetLimits limits;
    private final DescriptorDecoder v1;
    private final CubicDescriptorDecoder cubic;
    private final MorphDescriptorDecoder morph;
    public RuntimeDescriptorDecoder() { this(BlendAssetLimits.DEFAULT); }
    public RuntimeDescriptorDecoder(BlendAssetLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
        v1 = new DescriptorDecoder(limits);
        cubic = new CubicDescriptorDecoder(limits);
        morph = new MorphDescriptorDecoder(limits);
    }
    public ModelDescriptor decode(BlendResourceId modelKey, AssetBytes bytes) {
        Objects.requireNonNull(modelKey, "modelKey");
        Objects.requireNonNull(bytes, "bytes");
        // The strict decoder owns existing size/JSON diagnostics. Never parse an oversized descriptor here.
        if (bytes.size() > limits.maxGlbBytes()) return v1.decode(modelKey, bytes);
        try {
            if (StrictJsonParser.parse(bytes.copy()) instanceof JsonObject root
                    && root.get("format_version") instanceof JsonNumber number
                    && number.asDouble() == 2.0 && root.get("profile") instanceof JsonString profile) {
                if (ModelProfile.SKINNED_MORPH_CPU_V1.serializedName().equals(profile.value())) return morph.decode(modelKey, bytes);
                if (ModelProfile.SKINNED_CUBIC_V1.serializedName().equals(profile.value())) return cubic.decode(modelKey, bytes);
            }
        } catch (BlendAssetLoadException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // Preserve the original strict parser's diagnostic and location for malformed input.
        }
        return v1.decode(modelKey, bytes);
    }
}
