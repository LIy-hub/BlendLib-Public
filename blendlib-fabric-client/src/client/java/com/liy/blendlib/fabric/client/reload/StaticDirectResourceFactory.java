package com.liy.blendlib.fabric.client.reload;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.util.Objects;

/** Render-thread-only resource transaction for the direct-static D1 leaf. */
final class StaticDirectResourceFactory {
    interface BufferAllocator {
        void assertOnRenderThread();

        StaticDirectGenerationResources.BufferBindings allocate(X7GpuGenerationKey key, X7GeometryStaging staging);
    }

    @FunctionalInterface
    interface ResourceAssembler {
        StaticDirectGenerationResources assemble(
                X7GpuGenerationKey key, X7GeometryStaging staging, StaticDirectGenerationResources.BufferBindings buffers);
    }

    private StaticDirectResourceFactory() {
    }

    static Attempt prepare(X7GpuGenerationKey key, X7GeometryStaging staging) {
        return prepare(key, staging, MinecraftBufferAllocator.INSTANCE, StaticDirectResourceFactory::assemble);
    }

    /** Package-private seams pin rollback behavior without making test-only GPU handles part of the production API. */
    static Attempt prepare(
            X7GpuGenerationKey key,
            X7GeometryStaging staging,
            BufferAllocator allocator,
            ResourceAssembler assembler) {
        X7GpuGenerationKey checkedKey = Objects.requireNonNull(key, "key");
        X7GeometryStaging ownedStaging = Objects.requireNonNull(staging, "staging");
        BufferAllocator checkedAllocator = Objects.requireNonNull(allocator, "allocator");
        ResourceAssembler checkedAssembler = Objects.requireNonNull(assembler, "assembler");
        StaticDirectGenerationResources.BufferBindings buffers = null;
        try {
            requireDirectStaticKey(checkedKey);
            checkedAllocator.assertOnRenderThread();
            buffers = Objects.requireNonNull(checkedAllocator.allocate(checkedKey, ownedStaging), "direct-static buffers");
            return Attempt.success(checkedAssembler.assemble(checkedKey, ownedStaging, buffers));
        } catch (Throwable failure) {
            closeQuietly(buffers, failure);
            if (failure instanceof Error error) {
                throw error;
            }
            return Attempt.failure(failure);
        } finally {
            ownedStaging.close();
        }
    }

    private static StaticDirectGenerationResources assemble(
            X7GpuGenerationKey key,
            X7GeometryStaging staging,
            StaticDirectGenerationResources.BufferBindings buffers) {
        return new StaticDirectGenerationResources(
                key,
                staging.vertexCount(),
                staging.indexCount(),
                staging.vertexByteCount(),
                staging.indexByteCount(),
                buffers);
    }

    private static void requireDirectStaticKey(X7GpuGenerationKey key) {
        if (key.vertexFormat() != X7GpuVertexFormat.POSITION_NORMAL_UV_F32
                || key.indexType() != X7GpuIndexType.UINT32_LE
                || key.primitiveMode() != X7PrimitiveMode.TRIANGLES) {
            throw new IllegalArgumentException("The direct-static factory accepts only the frozen strict-v1 triangle layout");
        }
    }

    private static void closeQuietly(StaticDirectGenerationResources.BufferBindings buffers, Throwable primary) {
        if (buffers == null) {
            return;
        }
        try {
            buffers.close();
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
        public StaticDirectGenerationResources.BufferBindings allocate(X7GpuGenerationKey key, X7GeometryStaging staging) {
            GpuDevice device = RenderSystem.tryGetDevice();
            if (device == null) {
                device = RenderSystem.getDevice();
            }
            GpuBuffer vertex = null;
            GpuBuffer index = null;
            try {
                ByteBuffer vertexBytes = staging.vertexBytesForUpload();
                ByteBuffer indexBytes = staging.indexBytesForUpload();
                vertex = Objects.requireNonNull(device.createBuffer(
                        () -> label(key, "vertex"),
                        GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_VERTEX,
                        vertexBytes), "direct-static vertex buffer");
                index = Objects.requireNonNull(device.createBuffer(
                        () -> label(key, "index"),
                        GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_INDEX,
                        indexBytes), "direct-static index buffer");
                return StaticDirectGenerationResources.minecraft(vertex, index);
            } catch (Throwable failure) {
                closeQuietly(index, failure);
                closeQuietly(vertex, failure);
                StaticDirectResourceFactory.<RuntimeException>throwUnchecked(failure);
                throw new AssertionError("unreachable");
            }
        }

        private static String label(X7GpuGenerationKey key, String part) {
            return "blendlib-x7-static/" + key.generation() + "/" + key.modelId() + "/" + key.geometryId() + "/" + part;
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
        private final StaticDirectGenerationResources resources;
        private final Throwable failure;

        private Attempt(StaticDirectGenerationResources resources, Throwable failure) {
            this.resources = resources;
            this.failure = failure;
        }

        static Attempt success(StaticDirectGenerationResources resources) {
            return new Attempt(Objects.requireNonNull(resources, "resources"), null);
        }

        static Attempt failure(Throwable failure) {
            return new Attempt(null, Objects.requireNonNull(failure, "failure"));
        }

        boolean succeeded() {
            return resources != null;
        }

        StaticDirectGenerationResources resourcesOrNull() {
            return resources;
        }

        Throwable failureOrNull() {
            return failure;
        }
    }
}
