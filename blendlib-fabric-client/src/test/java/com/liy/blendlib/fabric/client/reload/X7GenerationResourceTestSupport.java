package com.liy.blendlib.fabric.client.reload;

import java.util.List;

/** Test-only fake complete leaves for legacy D1 attachment coverage during the T2a1/T2a2 split. */
final class X7GenerationResourceTestSupport {
    private X7GenerationResourceTestSupport() {
    }

    static CompletedGenerationResourceSet complete(
            ModelRegistryGeneration generation,
            int physicalResourceCount,
            CompletedGenerationResourceSet.PhysicalLeafClose close) {
        return complete(generation, physicalResourceCount, 0L, close);
    }

    static CompletedGenerationResourceSet complete(
            ModelRegistryGeneration generation,
            int physicalResourceCount,
            long physicalByteCount,
            CompletedGenerationResourceSet.PhysicalLeafClose close) {
        return CompletedGenerationResourceSet.complete(
                generation,
                List.of(CompletedGenerationResourceSet.leaf(
                        new X7GpuGenerationKey(
                                generation.generationId(),
                                "test:legacy-d1",
                                "test:complete-leaf",
                                "test:route",
                                0,
                                X7GpuVertexFormat.POSITION_NORMAL_UV_F32,
                                X7PrimitiveMode.TRIANGLES,
                                X7GpuIndexType.UINT32_LE),
                        physicalResourceCount,
                        physicalByteCount,
                        close)));
    }
}
