package com.liy.blendlib.core.descriptor;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.asset.AssetBytes;
import com.liy.blendlib.core.limits.BlendAssetLimits;

/** Version-2 skinned_cubic_v1 descriptor; shares only the frozen v1 field grammar. */
public final class CubicDescriptorDecoder {
    private final DescriptorDecoder fields;
    public CubicDescriptorDecoder() { this(BlendAssetLimits.DEFAULT); }
    public CubicDescriptorDecoder(BlendAssetLimits limits) { fields = new DescriptorDecoder(limits); }
    public ModelDescriptor decode(BlendResourceId modelKey, AssetBytes bytes) { return fields.decodeCubic(modelKey, bytes); }
}
