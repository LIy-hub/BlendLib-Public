package com.liy.blendlib.fabric.client.reload;

import com.liy.blendlib.fabric.client.api.ClientModelLookup;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import java.util.Objects;

/** Test-only access to the reload-private trusted lookup; it is absent from production artifacts. */
public final class ClientModelLookupTestSupport {
    private ClientModelLookupTestSupport() {
    }

    public static ClientModelLookup sourceOwnedLookup(ClientModelRegistry registry) {
        return new RegistryBackedModelLookup(Objects.requireNonNull(registry, "registry"));
    }

    /** Test-only exact-admission interleaving seam for real registry-backed reload races. */
    public static ClientModelLookup sourceOwnedLookup(
            ClientModelRegistry registry, Runnable afterExactLeaseAdmission) {
        return new RegistryBackedModelLookup(
                Objects.requireNonNull(registry, "registry"),
                Objects.requireNonNull(afterExactLeaseAdmission, "afterExactLeaseAdmission"));
    }

    public static int outstandingLeaseCount(ClientModelRegistry registry) {
        return Objects.requireNonNull(registry, "registry").resourceLifecycleDiagnostics().outstandingLeaseCount();
    }

    public static ClientGenerationLeaseBinding sourceOwnedBinding(
            ClientModelRegistry registry, BlendModelKey modelKey, ModelRenderSnapshot snapshot) {
        return sourceOwnedLookup(registry).acquireGenerationLeaseBinding(
                Objects.requireNonNull(modelKey, "modelKey"), Objects.requireNonNull(snapshot, "snapshot"));
    }
}
