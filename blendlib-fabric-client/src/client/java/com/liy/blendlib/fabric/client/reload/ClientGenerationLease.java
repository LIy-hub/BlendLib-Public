package com.liy.blendlib.fabric.client.reload;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.fabric.client.api.ClientModelLookup;
import com.liy.blendlib.fabric.client.api.ClientModelView;
import com.liy.blendlib.fabric.client.render.ModelRenderHandle;
import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import com.liy.blendlib.fabric.client.render.X6DrawPrimitive;
import java.util.Objects;

/**
 * Opaque ownership of one exact registry generation's render resources.
 *
 * <p>Managed issuance is package-private and occurs only in the trusted registry-backed lookup.
 * The legacy keyed lookup method remains source-compatible, but X4/X6 managed admission accepts
 * only a source-bound {@link ClientGenerationLeaseBinding} sampled by that lookup. This type
 * exposes neither the resource owner nor any GPU/Minecraft type. {@link #unavailable()} is an
 * explicit CPU/provider-only fallback; it is not a managed lease. This object covers prepared
 * snapshot/plan ownership only. It is <strong>not</strong> a render-pass, collector-callback, or
 * deferred-draw completion receipt.</p>
 */
public final class ClientGenerationLease implements AutoCloseable {
    private static final ClientGenerationLease UNAVAILABLE = new ClientGenerationLease(
            null, null, null, -1L, null, null, null, false);

    private final GenerationRenderResourceLease delegate;
    private final ClientModelLookup sourceLookup;
    private final BlendModelKey bindingKey;
    private final long bindingGeneration;
    private final ModelRegistryGeneration bindingGenerationInstance;
    private final ModelHandle bindingModelHandle;
    private final ModelRenderHandle bindingHandle;
    private final boolean syntheticMissingBinding;

    private ClientGenerationLease(
            GenerationRenderResourceLease delegate,
            ClientModelLookup sourceLookup,
            BlendModelKey bindingKey,
            long bindingGeneration,
            ModelRegistryGeneration bindingGenerationInstance,
            ModelHandle bindingModelHandle,
            ModelRenderHandle bindingHandle,
            boolean syntheticMissingBinding) {
        this.delegate = delegate;
        this.sourceLookup = sourceLookup;
        this.bindingKey = bindingKey;
        this.bindingGeneration = bindingGeneration;
        this.bindingGenerationInstance = bindingGenerationInstance;
        this.bindingModelHandle = bindingModelHandle;
        this.bindingHandle = bindingHandle;
        this.syntheticMissingBinding = syntheticMissingBinding;
    }

    /** Returns the singleton explicit no-resource fallback for unmanaged or missing views. */
    public static ClientGenerationLease unavailable() {
        return UNAVAILABLE;
    }

    /**
     * Package-private issuance for one concrete registry-backed lookup. The lookup identity is
     * retained independently of a caller-provided view so wrappers cannot present another source
     * as the issuing registry.
     */
    static ClientGenerationLease acquire(
            ClientModelRegistry registry, ClientModelLookup sourceLookup, BlendModelKey modelKey) {
        ClientModelRegistry checkedRegistry = Objects.requireNonNull(registry, "registry");
        ClientModelLookup checkedSourceLookup = Objects.requireNonNull(sourceLookup, "sourceLookup");
        BlendModelKey checkedModelKey = Objects.requireNonNull(modelKey, "modelKey");
        ModelRegistryGeneration sampled = checkedRegistry.current();
        ModelHandle sampledHandle = sampled.handles().get(checkedModelKey);
        ModelRenderHandle sampledRenderHandle = sampledHandle == null ? null : sampledHandle.renderHandle();
        GenerationRenderResourceLease acquired = sampled.acquireExactRenderResourceLease(
                checkedModelKey, sampledHandle, sampledRenderHandle);
        if (sampledHandle == null) {
            return new ClientGenerationLease(
                    null,
                    checkedSourceLookup,
                    checkedModelKey,
                    sampled.generationId(),
                    sampled,
                    null,
                    null,
                    true);
        }
        return new ClientGenerationLease(
                acquired,
                checkedSourceLookup,
                checkedModelKey,
                sampled.generationId(),
                sampled,
                sampledHandle,
                sampledRenderHandle,
                false);
    }

    /** Whether this instance owns an admitted D1 generation count rather than an explicit fallback. */
    public boolean managed() {
        return delegate != null;
    }

    /**
     * Package-private source admission used only while the trusted lookup builds its immutable
     * composite. An unavailable external fallback is explicitly accepted as no-resource; every
     * source-bound token must match the exact issuing lookup, key, generation, and handle.
     */
    void requireCompatible(ClientModelLookup expectedSourceLookup, ClientModelView view) {
        ClientModelLookup checkedSourceLookup = Objects.requireNonNull(expectedSourceLookup, "expectedSourceLookup");
        ClientModelView checkedView = Objects.requireNonNull(view, "view");
        if (bindingKey == null) {
            return;
        }
        if (sourceLookup != checkedSourceLookup) {
            throw new IllegalArgumentException("Shared generation lease belongs to another concrete model lookup source");
        }
        if (!bindingKey.equals(checkedView.key()) || bindingGeneration != checkedView.generationId()) {
            throw new IllegalArgumentException("Shared generation lease belongs to another registry generation or model key");
        }
        if (syntheticMissingBinding) {
            if (!checkedView.discovered() && checkedView.missing()) {
                return;
            }
            throw new IllegalArgumentException("Shared generation lease requires the exact not-discovered missing fallback");
        }
        if (bindingHandle != checkedView.renderHandle()) {
            throw new IllegalArgumentException("Shared generation lease requires the exact source-owned render-handle identity");
        }
        if (delegate != null && delegate.isReleased()) {
            throw new IllegalStateException("Shared generation lease has already been released");
        }
    }

    /**
     * Fails closed unless this still-open managed lease was admitted for this exact handle object.
     * It is intended for additive prepared-plan handoff validation, not for lookup or retention.
     */
    public void requireExactHandle(ModelRenderHandle handle) {
        ModelRenderHandle checkedHandle = Objects.requireNonNull(handle, "handle");
        if (delegate == null) {
            throw new IllegalStateException("Shared generation ownership has no managed render-resource parent");
        }
        if (delegate.isReleased()) {
            throw new IllegalStateException("Shared generation lease has already been released");
        }
        if (bindingHandle != checkedHandle || delegate.handle().renderHandle() != checkedHandle) {
            throw new IllegalArgumentException("Shared generation lease requires the exact admitted render-handle identity");
        }
    }

    /**
     * Rechecks a real source-owned no-resource missing sample under the D1 owner lock.
     *
     * <p>Not-discovered missing handles are allocated by {@code resolve}, so their object identity
     * cannot be compared across two resolves. A map-owned missing handle must retain exact raw and
     * render-handle identity. In both cases the supplied immutable snapshot must describe the
     * sampled key/generation and a missing handle. Loaded samples never enter this path: a reload
     * or handle change must remain a fail-closed managed-admission failure rather than downgrade
     * to no-resource ownership.</p>
     */
    void requireCurrentMissingSnapshot(ModelRenderSnapshot snapshot) {
        ModelRenderSnapshot checkedSnapshot = Objects.requireNonNull(snapshot, "snapshot");
        if (delegate != null || bindingKey == null || bindingGenerationInstance == null) {
            throw new IllegalStateException("Shared generation lease is not a source-owned no-resource missing sample");
        }
        if (!bindingKey.equals(checkedSnapshot.handle().modelKey())
                || bindingGeneration != checkedSnapshot.generation()
                || !checkedSnapshot.handle().missingModel()) {
            throw new IllegalArgumentException(
                    "Shared generation missing fallback requires the sampled key, generation, and missing handle");
        }
        if (syntheticMissingBinding) {
            if (bindingModelHandle != null || bindingHandle != null) {
                throw new AssertionError("Synthetic missing samples must not retain a map-owned handle");
            }
            bindingGenerationInstance.requireExactCurrentMissingBinding(bindingKey, null, null);
            return;
        }
        if (bindingModelHandle == null
                || bindingHandle == null
                || bindingModelHandle.renderHandle() != bindingHandle
                || bindingHandle != checkedSnapshot.handle()) {
            throw new IllegalArgumentException(
                    "Shared generation map-owned missing fallback requires the exact sampled render-handle identity");
        }
        bindingGenerationInstance.requireExactCurrentMissingBinding(
                bindingKey, bindingModelHandle, bindingHandle);
    }

    /**
     * Package-private child mint for an already plan-owned parent.  The public API receives only an opaque
     * close-only receipt; neither this method nor its result leaks from the reload package.
     */
    X7DeferredSubmissionBridge.SubmissionChild beginDeferredSubmission(
            ModelRenderSnapshot exactSnapshot, X6DrawPrimitive exactDraw) {
        if (delegate == null) {
            throw new IllegalStateException("Shared generation ownership has no managed D1 parent for deferred submission");
        }
        if (delegate.isReleased()) {
            throw new IllegalStateException("Shared generation parent lease has already been released");
        }
        return X7DeferredSubmissionBridge.begin(delegate, exactSnapshot, exactDraw);
    }

    /** Releases this parent ownership at most once. The unavailable fallback is a no-op. */
    @Override
    public void close() {
        if (delegate != null) {
            delegate.close();
        }
    }
}
