package com.liy.blendlib.fabric.client.animation.runtime;

import com.liy.blendlib.core.model.ModelNode;
import java.util.List;

/** Test-source bridge for the package-private rig-view preparation factory. */
public final class ClientAnimationRigViewTestAccess {
    private ClientAnimationRigViewTestAccess() {
    }

    public static ClientAnimationRigView fromNodes(List<ModelNode> nodes) {
        return ClientAnimationRigView.fromNodes(nodes);
    }
}
