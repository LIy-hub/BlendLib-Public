package com.liy.blendlib.core.descriptor;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.asset.AssetBytes;
import com.liy.blendlib.core.limits.BlendAssetLimits;

/** Exact format-2 bounded CPU morph descriptor entry point. */
public final class MorphDescriptorDecoder {
    private final DescriptorDecoder fields;
    public MorphDescriptorDecoder() { this(BlendAssetLimits.DEFAULT); }
    public MorphDescriptorDecoder(BlendAssetLimits limits) { fields = new DescriptorDecoder(limits); }
    public ModelDescriptor decode(BlendResourceId modelKey, AssetBytes bytes) { return fields.decodeMorph(modelKey, bytes); }
}
