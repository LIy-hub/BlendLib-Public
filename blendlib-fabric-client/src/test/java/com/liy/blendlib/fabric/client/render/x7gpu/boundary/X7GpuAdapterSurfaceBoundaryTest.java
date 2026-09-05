package com.liy.blendlib.fabric.client.render.x7gpu.boundary;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Modifier;
import java.util.List;
import org.junit.jupiter.api.Test;

class X7GpuAdapterSurfaceBoundaryTest {
    private static final String DRAW_PACKAGE = "com.liy.blendlib.fabric.client.render.x7gpu.";
    private static final String RELOAD_PACKAGE = "com.liy.blendlib.fabric.client.reload.";

    @Test
    void drawCandidatesAndMovedResourceIslandTypesRemainNonPublicOutsideTheirImplementationPackages()
            throws ClassNotFoundException {
        assertNonPublic(DRAW_PACKAGE, List.of(
                "X7GpuDiagnosticsCollector",
                "X7GpuDiagnosticsSnapshot",
                "X7StaticBatchKey",
                "X7StaticBatchInput",
                "X7StaticBatchLimits",
                "X7StaticBatchPlan",
                "X7StaticBatchPlanner",
                "X7StaticBatchVertexFormat",
                "X7CpuSkinnedUploadCandidate",
                "X7CpuSkinnedUploadCandidate$Limits",
                "X7CpuSkinnedUploadCandidate$PackedGeometry"));
        assertNonPublic(RELOAD_PACKAGE, List.of(
                "X7ImmutableGeometryView",
                "X7GeometryStaging",
                "X7GeometryStagingLimits",
                "X7GpuBuffer",
                "X7GpuDevice",
                "X7Minecraft2612GpuDevice",
                "X7GpuResourceFactory",
                "X7GpuResourceFactory$Attempt",
                "X7GpuResourceFactory$Attempt$Outcome",
                "X7GpuResourceFactory$Attempt$FailureStage",
                "X7GpuResourceFactory$Attempt$ResourceFailure",
                "X7GpuResourceFactory$ResourceAssembler",
                "X7SharedGeometryResources",
                "X7GpuGenerationKey",
                "X7GpuVertexFormat",
                "X7GpuIndexType",
                "X7PrimitiveMode",
                "X7GenerationResourceBridge",
                "X7GenerationResourceBridge$AuthoritativeGenerationInventory",
                "X7GenerationResourceBridge$AuthoritativeGenerationInventory$PrimitiveFamily",
                "X7GenerationResourceBridge$AuthoritativeGenerationInventory$AuthoritativeEntry",
                "X7GenerationResourceBridge$AuthoritativeGenerationInventory$InventoryProof",
                "X7GenerationResourceBridge$FrozenSelectionSet",
                "X7GenerationResourceBridge$FrozenSelectionSet$CompleteClassificationProof",
                "X7GenerationResourceBridge$FrozenSelection",
                "X7GenerationResourceBridge$ResourceAttempt",
                "X7GenerationResourceBridge$Composition",
                "X7GenerationResourceBridge$Composition$Route",
                "CompletedGenerationResourceSet",
                "CompletedGenerationResourceSet$ResourceLeaf",
                "CompletedGenerationResourceSet$PhysicalLeafClose",
                "CompletedGenerationResourceSet$CleanupFailureSelector",
                "CompletedGenerationResourceSet$ClaimMove",
                "CompletedGenerationResourceSet$D1Adoption"));
    }

    @Test
    void retiredB1LifecycleSourcesCannotRemainInTheDrawPackage() {
        for (String className : List.of(
                "X7GpuGeneration.java",
                "X7GpuGenerationLease.java",
                "X7GenerationHoldKind.java",
                "X7GpuCloseScheduler.java",
                "X7RenderOwner.java",
                "X7Minecraft2612RenderOwner.java",
                "X7GpuGenerationDiagnostics.java")) {
            assertFalse(java.nio.file.Files.exists(java.nio.file.Path.of(
                    System.getProperty("blendlib.projectDir"),
                    "src", "client", "java", "com", "liy", "blendlib", "fabric", "client", "render", "x7gpu", className)),
                    className);
        }
    }

    private static void assertNonPublic(String packageName, List<String> classNames) throws ClassNotFoundException {
        for (String className : classNames) {
            Class<?> type = Class.forName(packageName + className);
            assertFalse(Modifier.isPublic(type.getModifiers()), className);
            assertFalse(Modifier.isProtected(type.getModifiers()), className);
        }
    }
}
