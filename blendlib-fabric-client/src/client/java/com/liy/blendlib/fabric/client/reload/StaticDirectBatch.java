package com.liy.blendlib.fabric.client.reload;

import com.liy.blendlib.api.BlendResourceId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One immutable, same-D1-model-and-texture direct-static batch.
 *
 * <p>Batch construction is intentionally separate from the queue. T3c may group only already-queued requests for
 * one exact AFTER_SOLID frame; it cannot change queue ownership or manufacture a request.</p>
 */
final class StaticDirectBatch {
    static final int MAX_INSTANCES = 16;

    private final StaticDirectGenerationResources exactResources;
    private final X7AfterSolidFrameIdentity exactTargetFrame;
    private final BlendResourceId exactTextureId;
    private final List<Entry> entries;
    private final List<X7DeferredFrameQueue.QueuedSubmission> queuedSubmissions;
    private final StaticDirectInstanceUpload instanceUpload;

    private StaticDirectBatch(
            StaticDirectGenerationResources exactResources,
            X7AfterSolidFrameIdentity exactTargetFrame,
            BlendResourceId exactTextureId,
            List<Entry> entries) {
        this.exactResources = Objects.requireNonNull(exactResources, "exactResources");
        this.exactTargetFrame = Objects.requireNonNull(exactTargetFrame, "exactTargetFrame");
        this.exactTextureId = Objects.requireNonNull(exactTextureId, "exactTextureId");
        this.entries = List.copyOf(entries);
        if (this.entries.isEmpty() || this.entries.size() > MAX_INSTANCES) {
            throw new IllegalArgumentException("The direct-static batch must be non-empty and bounded");
        }
        this.queuedSubmissions = this.entries.stream().map(Entry::queued).toList();
        this.instanceUpload = StaticDirectInstanceUpload.pack(this.entries.stream().map(Entry::inputs).toList());
    }

    static Builder builder(
            X7DeferredFrameQueue.QueuedSubmission queued,
            StaticDirectFrameInputs inputs,
            StaticDirectGenerationResources resources) {
        return new Builder(queued, inputs, resources);
    }

    StaticDirectGenerationResources exactResources() {
        return exactResources;
    }

    X7AfterSolidFrameIdentity exactTargetFrame() {
        return exactTargetFrame;
    }

    BlendResourceId exactTextureId() {
        return exactTextureId;
    }

    List<Entry> entries() {
        return entries;
    }

    int instanceCount() {
        return entries.size();
    }

    StaticDirectInstanceUpload instanceUpload() {
        return instanceUpload;
    }

    /** Returns a complete immutable receipt set, or fails before any queue child can detach. */
    List<X7DeferredSubmissionCompletionReceipt> recordCommandsAtomically() {
        return queuedSubmissions.getFirst().recordCommandsAtomically(queuedSubmissions);
    }

    void cancelNoCommand() {
        Throwable primary = null;
        for (Entry entry : entries) {
            try {
                entry.queued().cancelNoCommand();
            } catch (Throwable failure) {
                if (primary == null) {
                    primary = failure;
                } else if (primary != failure) {
                    primary.addSuppressed(failure);
                }
            }
        }
        if (primary != null) {
            CompletedGenerationResourceSet.throwUnchecked(primary);
        }
    }

    record Entry(X7DeferredFrameQueue.QueuedSubmission queued, StaticDirectFrameInputs inputs) {
        Entry {
            queued = Objects.requireNonNull(queued, "queued");
            inputs = Objects.requireNonNull(inputs, "inputs");
        }
    }

    static final class Builder {
        private final StaticDirectGenerationResources exactResources;
        private final X7AfterSolidFrameIdentity exactTargetFrame;
        private final BlendResourceId exactTextureId;
        private final List<Entry> entries = new ArrayList<>(MAX_INSTANCES);
        private boolean built;

        private Builder(
                X7DeferredFrameQueue.QueuedSubmission queued,
                StaticDirectFrameInputs inputs,
                StaticDirectGenerationResources resources) {
            this.exactResources = Objects.requireNonNull(resources, "resources");
            StaticDirectFrameInputs checkedInputs = Objects.requireNonNull(inputs, "inputs");
            this.exactTargetFrame = Objects.requireNonNull(queued, "queued").request().targetFrame();
            this.exactTextureId = checkedInputs.exactMarker().exactMaterial().textureId();
            entries.add(new Entry(queued, checkedInputs));
        }

        /** Returns false without mutating the builder when a candidate cannot share this exact bounded batch. */
        boolean tryAdd(
                X7DeferredFrameQueue.QueuedSubmission queued,
                StaticDirectFrameInputs inputs,
                StaticDirectGenerationResources resources) {
            if (built || entries.size() >= MAX_INSTANCES) {
                return false;
            }
            X7DeferredFrameQueue.QueuedSubmission checkedQueued = Objects.requireNonNull(queued, "queued");
            StaticDirectFrameInputs checkedInputs = Objects.requireNonNull(inputs, "inputs");
            if (resources != exactResources
                    || !exactTargetFrame.equals(checkedQueued.request().targetFrame())
                    || !exactTextureId.equals(checkedInputs.exactMarker().exactMaterial().textureId())
                    || entries.stream().anyMatch(entry -> entry.queued() == checkedQueued)) {
                return false;
            }
            entries.add(new Entry(checkedQueued, checkedInputs));
            return true;
        }

        StaticDirectBatch build() {
            if (built) {
                throw new IllegalStateException("The immutable direct-static batch has already been built");
            }
            built = true;
            return new StaticDirectBatch(exactResources, exactTargetFrame, exactTextureId, entries);
        }
    }
}
