package com.liy.blendlib.fabric.client;

import com.liy.blendlib.fabric.client.render.X7DeferredSubmissionEndpoint;
import java.util.Objects;

/**
 * Fabric 1.21.9 has no public world-render pass-owner events.
 * The released CPU submission path remains active. The unreleased X7 endpoint
 * cannot install here, and no callback or native resource is fabricated.
 */
final class X7Minecraft2612PassOwnerHost {
    enum Phase { AFTER_SOLID_FEATURES, BEFORE_TRANSLUCENT_TERRAIN }

    private X7Minecraft2612PassOwnerHost() { }

    static X7Minecraft2612PassOwnerHost production() {
        return new X7Minecraft2612PassOwnerHost();
    }

    static X7Minecraft2612PassOwnerHost production(X7DeferredSubmissionEndpoint endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        if (endpoint.mayInstallHostEndpoint()) {
            throw new UnsupportedOperationException("Fabric 1.21.9 has no public X7 pass-owner events");
        }
        return production();
    }

    void install() {
        // There is no supported event to register on this target.
    }
}
