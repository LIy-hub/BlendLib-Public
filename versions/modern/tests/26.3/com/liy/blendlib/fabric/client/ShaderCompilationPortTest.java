package com.liy.blendlib.fabric.client;

import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.frontend.shaders.SPIRVModule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.shaderc.ShadercIncludeResolve;
import org.lwjgl.util.shaderc.ShadercIncludeResultRelease;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.util.shaderc.Shaderc.*;

/** Uses the client's native ShaderC options and SPIR-V reflection without a GPU device. */
class ShaderCompilationPortTest {
    @Test void allBundledShaderStagesCompileWithoutAWindow() throws Exception {
        var defines = ShaderDefines.builder().define("X7_SKINNED_MAX_BONES", 64)
                .define("X7_STATIC_MAX_INSTANCES", 16).build();
        for (boolean zeroToOne : new boolean[] {false, true}) {
            try (var sources = new ClasspathShaderSource()) {
                for (String shader : new String[] {"x7_static_rigid", "x7_static_direct", "x7_skinned"}) {
                    var id = Identifier.fromNamespaceAndPath("blendlib", "core/" + shader);
                    for (ShaderType type : ShaderType.values()) {
                        try (var module = compile(id, type, defines, sources, zeroToOne)) {
                            assertTrue(module.spv().hasRemaining(), id + " " + type);
                            module.reflect();
                        }
                    }
                }
            }
        }
    }

    private static SPIRVModule compile(Identifier id, ShaderType type, ShaderDefines defines,
            ClasspathShaderSource sources, boolean zeroToOne) {
        long compiler = shaderc_compiler_initialize();
        long options = shaderc_compile_options_initialize();
        // Mirrors GlslCompiler.createBaseShaderOptions; no live RenderSystem device is required.
        shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
        shaderc_compile_options_set_auto_bind_uniforms(options, true);
        shaderc_compile_options_set_preserve_bindings(options, false);
        shaderc_compile_options_set_generate_debug_info(options);
        shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_zero);
        if (zeroToOne) shaderc_compile_options_add_macro_definition(options, "RENDERPEARL_DEPTH_IS_ZERO_TO_ONE", "");
        shaderc_compile_options_add_macro_definition(options, "RENDERPEARL_INSTANCE_INDEX_INCLUDES_BASE_INSTANCE", "");
        defines.values().forEach((key, value) -> shaderc_compile_options_add_macro_definition(options, key, value));
        try (var resolver = ShadercIncludeResolve.create((user, requested, includeType, source, depth) ->
                    sources.getInclude(Identifier.parse(memASCII(requested))).includeResultPtr());
                var release = ShadercIncludeResultRelease.create((user, included) -> {})) {
            shaderc_compile_options_set_include_callbacks(options, resolver, release, 0);
            long result = shaderc_compile_into_spv(compiler, sources.getShader(id, type),
                    type == ShaderType.VERTEX ? shaderc_vertex_shader : shaderc_fragment_shader,
                    id.toString(), "main", options);
            try {
                assertEquals(shaderc_compilation_status_success, shaderc_result_get_compilation_status(result),
                        id + " " + type + ": " + shaderc_result_get_error_message(result));
                var bytes = shaderc_result_get_bytes(result);
                var ownedBytes = memCalloc(bytes.remaining());
                memCopy(bytes, ownedBytes);
                return new SPIRVModule(ownedBytes, type);
            } finally {
                shaderc_result_release(result);
            }
        } finally {
            shaderc_compile_options_release(options);
            shaderc_compiler_release(compiler);
        }
    }

    private static final class ClasspathShaderSource implements ShaderSource {
        private final Map<Identifier, CachedIncludeSource> includes = new HashMap<>();

        @Override public String getShader(Identifier id, ShaderType type) {
            return read("/assets/" + id.getNamespace() + "/shaders/" + id.getPath()
                    + (type == ShaderType.VERTEX ? ".vsh" : ".fsh"));
        }

        @Override public CachedIncludeSource getInclude(Identifier id) {
            return includes.computeIfAbsent(id, key -> CachedIncludeSource.create(key,
                    read("/assets/" + key.getNamespace() + "/shaders/include/" + key.getPath())));
        }

        @Override public void close() {
            includes.values().forEach(CachedIncludeSource::close);
        }

        private static String read(String path) {
            try (var input = ShaderCompilationPortTest.class.getResourceAsStream(path)) {
                if (input == null) throw new IllegalStateException("Missing shader resource: " + path);
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException exception) {
                throw new IllegalStateException("Cannot read shader resource: " + path, exception);
            }
        }
    }
}
