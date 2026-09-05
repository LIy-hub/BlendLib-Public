package com.liy.blendlib.fabric.client.reload;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.util.Objects;

/** Render-thread-only all-or-nothing factory for the one T4 skinned D1 leaf. */
final class SkinnedTexelResourceFactory {
    interface BufferAllocator {
        void assertOnRenderThread();

        SkinnedTexelGenerationResources.BufferBindings allocate(
                X7GpuGenerationKey key, SkinnedTexelUploadStaging staging);
    }

    @FunctionalInterface
    interface ResourceAssembler {
        SkinnedTexelGenerationResources assemble(
                X7GpuGenerationKey key,
                SkinnedTexelUploadStaging staging,
                SkinnedTexelGenerationResources.BufferBindings bindings);
    }

    private SkinnedTexelResourceFactory() {
    }

    static Attempt prepare(X7GpuGenerationKey key, SkinnedTexelUploadStaging staging) {
        return prepare(key, staging, MinecraftBufferAllocator.INSTANCE, SkinnedTexelResourceFactory::assemble);
    }

    /** Package-private seams prove all-or-nothing rollback without leaking native handles to tests or T3c. */
    static Attempt prepare(
            X7GpuGenerationKey key,
            SkinnedTexelUploadStaging staging,
            BufferAllocator allocator,
            ResourceAssembler assembler) {
        X7GpuGenerationKey checkedKey = Objects.requireNonNull(key, "key");
        SkinnedTexelUploadStaging ownedStaging = Objects.requireNonNull(staging, "staging");
        SkinnedTexelGenerationResources.BufferBindings bindings = null;
        try {
            requireSkinnedKey(checkedKey);
            Objects.requireNonNull(allocator, "allocator").assertOnRenderThread();
            bindings = Objects.requireNonNull(
                    allocator.allocate(checkedKey, ownedStaging), "skinned texel buffer bindings");
            SkinnedTexelGenerationResources resources = Objects.requireNonNull(
                    Objects.requireNonNull(assembler, "assembler").assemble(checkedKey, ownedStaging, bindings),
                    "skinned texel resources");
            // Allocate the return shell before the owner moves; a later Error still leaves staging able to roll back.
            Attempt result = Attempt.success(resources);
            // Complete the leaf only after all fallible construction; this state handoff allocates nothing.
            ownedStaging.transferStaticSourceToLeaf(resources);
            return result;
        } catch (Throwable failure) {
            closeQuietly(bindings, failure);
            if (failure instanceof Error error) {
                throw error;
            }
            return Attempt.failure(failure);
        } finally {
            ownedStaging.close();
        }
    }

    private static SkinnedTexelGenerationResources assemble(
            X7GpuGenerationKey key,
            SkinnedTexelUploadStaging staging,
            SkinnedTexelGenerationResources.BufferBindings bindings) {
        return new SkinnedTexelGenerationResources(
                key,
                staging.staticSource(),
                staging.vertexCount(),
                staging.indexCount(),
                staging.vertexByteCount(),
                staging.indexByteCount(),
                staging.sourceByteCount(),
                bindings);
    }

    private static void requireSkinnedKey(X7GpuGenerationKey key) {
        if (key.vertexFormat() != X7GpuVertexFormat.POSITION_NORMAL_UV_F32
                || key.indexType() != X7GpuIndexType.UINT32_LE
                || key.primitiveMode() != X7PrimitiveMode.TRIANGLES) {
            throw new IllegalArgumentException("The skinned texel factory accepts only the frozen strict-v1 triangle layout");
        }
    }

    private static void closeQuietly(SkinnedTexelGenerationResources.BufferBindings bindings, Throwable primary) {
        if (bindings == null) {
            return;
        }
        try {
            bindings.close();
        } catch (Throwable closeFailure) {
            if (closeFailure != primary) {
                primary.addSuppressed(closeFailure);
            }
        }
    }

    private enum MinecraftBufferAllocator implements BufferAllocator {
        INSTANCE;

        @Override
        public void assertOnRenderThread() {
            RenderSystem.assertOnRenderThread();
        }

        @Override
        public SkinnedTexelGenerationResources.BufferBindings allocate(
                X7GpuGenerationKey key, SkinnedTexelUploadStaging staging) {
            GpuDevice device = RenderSystem.tryGetDevice();
            if (device == null) {
                device = RenderSystem.getDevice();
            }
            GpuBuffer vertex = null;
            GpuBuffer index = null;
            GpuBuffer source = null;
            try {
                vertex = Objects.requireNonNull(device.createBuffer(
                        () -> label(key, "vertex"),
                        GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_VERTEX,
                        staging.vertexBytesForUpload()), "skinned texel vertex buffer");
                index = Objects.requireNonNull(device.createBuffer(
                        () -> label(key, "index"),
                        GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_INDEX,
                        staging.indexBytesForUpload()), "skinned texel index buffer");
                source = Objects.requireNonNull(device.createBuffer(
                        () -> label(key, "source-texel"),
                        GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER,
                        staging.sourceBytesForUpload()), "skinned texel source buffer");
                return SkinnedTexelGenerationResources.minecraft(vertex, index, source);
            } catch (Throwable failure) {
                closeQuietly(source, failure);
                closeQuietly(index, failure);
                closeQuietly(vertex, failure);
                SkinnedTexelResourceFactory.<RuntimeException>throwUnchecked(failure);
                throw new AssertionError("unreachable");
            }
        }

        private static String label(X7GpuGenerationKey key, String part) {
            return "blendlib-x7-skinned/" + key.generation() + "/" + key.modelId() + "/" + key.geometryId() + "/" + part;
        }

        private static void closeQuietly(GpuBuffer buffer, Throwable primary) {
            if (buffer == null) {
                return;
            }
            try {
                buffer.close();
            } catch (Throwable closeFailure) {
                if (closeFailure != primary) {
                    primary.addSuppressed(closeFailure);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void throwUnchecked(Throwable failure) throws T {
        throw (T) failure;
    }

    static final class Attempt {
        private final SkinnedTexelGenerationResources resources;
        private final Throwable failure;

        private Attempt(SkinnedTexelGenerationResources resources, Throwable failure) {
            this.resources = resources;
            this.failure = failure;
        }

        static Attempt success(SkinnedTexelGenerationResources resources) {
            return new Attempt(Objects.requireNonNull(resources, "resources"), null);
        }

        static Attempt failure(Throwable failure) {
            return new Attempt(null, Objects.requireNonNull(failure, "failure"));
        }

        boolean succeeded() {
            return resources != null;
        }

        SkinnedTexelGenerationResources resourcesOrNull() {
            return resources;
        }

        Throwable failureOrNull() {
            return failure;
        }
    }
}
