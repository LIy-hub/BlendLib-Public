package com.liy.blendlib.core.loader;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.diagnostic.BlendAssetLoadException;
import com.liy.blendlib.core.diagnostic.BlendDiagnostic;

/** Package-private factory keeping strict-loader failures structured and bounded. */
final class LoaderFailure {
    private LoaderFailure() {
    }

    static BlendAssetLoadException error(
            String code,
            BlendResourceId modelKey,
            BlendResourceId resourceId,
            String location,
            String message) {
        return new BlendAssetLoadException(BlendDiagnostic.error(code, modelKey, resourceId, location, message));
    }

    static BlendAssetLoadException error(
            String code,
            BlendResourceId modelKey,
            BlendResourceId resourceId,
            String location,
            String message,
            Throwable cause) {
        return new BlendAssetLoadException(BlendDiagnostic.error(code, modelKey, resourceId, location, message), cause);
    }
}
