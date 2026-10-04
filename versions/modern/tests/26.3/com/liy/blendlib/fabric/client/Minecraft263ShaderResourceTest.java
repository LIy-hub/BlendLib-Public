package com.liy.blendlib.fabric.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.util.shaderc.Shaderc.*;

/** Compiles the processed production resources with 26.3's ShaderC target, without a window/GPU. */
class Minecraft263ShaderResourceTest {
    private static final String PREFIX = "assets/blendlib/shaders/core/";
    private static final Pattern INCLUDE = Pattern.compile("(?m)^#include <([^:>]+):([^>]+)>$");
    private static final Pattern INTERFACE = Pattern.compile(
            "(?m)^layout\\(location = (\\d+)\\) (in|out) (\\w+) (\\w+);$");

    @ParameterizedTest
    @ValueSource(strings = {"x7_skinned.vsh", "x7_skinned.fsh", "x7_static_direct.vsh",
            "x7_static_direct.fsh", "x7_static_rigid.vsh", "x7_static_rigid.fsh"})
    void processedShaderCompilesToSpirV(String name) throws IOException {
        String source = resource(PREFIX + name);
        assertFalse(source.contains("#moj_import"));
        assertFalse(source.contains("gl_VertexID"));
        assertFalse(source.contains("gl_InstanceID"));
        assertEquals("", compile(name, expandIncludes(source)), name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"x7_skinned", "x7_static_direct", "x7_static_rigid"})
    void stageInterfacesMatchByLocationAndType(String name) throws IOException {
        assertEquals(interfaceTypes(resource(PREFIX + name + ".vsh"), "out"),
                interfaceTypes(resource(PREFIX + name + ".fsh"), "in"));
        assertEquals(Map.of(0, "vec4"), interfaceTypes(resource(PREFIX + name + ".fsh"), "out"));
    }

    @Test
    void vertexLocationsMatchThePortedVertexFormats() throws IOException {
        assertTrue(resource(PREFIX + "x7_skinned.vsh").contains("layout(location = 1) in vec2 UV0;"));
        String direct = resource(PREFIX + "x7_static_direct.vsh");
        assertTrue(direct.contains("layout(location = 0) in vec3 Position;"));
        assertTrue(direct.contains("layout(location = 1) in vec3 Normal;"));
        assertTrue(direct.contains("layout(location = 2) in vec2 UV0;"));
    }

    @Test
    void compilerRejectsTheOriginalMissingLocations() throws IOException {
        String source = expandIncludes(resource(PREFIX + "x7_static_rigid.fsh"));
        assertFalse(compile("x7_static_rigid.fsh", source.replace("layout(location = 0) out", "out")).isEmpty());
    }

    private static Map<Integer, String> interfaceTypes(String source, String direction) {
        Map<Integer, String> result = new LinkedHashMap<>();
        var matcher = INTERFACE.matcher(source);
        while (matcher.find()) {
            if (matcher.group(2).equals(direction)) {
                assertNull(result.put(Integer.parseInt(matcher.group(1)), matcher.group(3)), "Duplicate location");
            }
        }
        return result;
    }

    private static String resource(String path) throws IOException {
        try (var input = Minecraft263ShaderResourceTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(input, path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String expandIncludes(String source) throws IOException {
        var matcher = INCLUDE.matcher(source);
        var expanded = new StringBuilder();
        while (matcher.find()) {
            String include = resource("assets/" + matcher.group(1) + "/shaders/include/" + matcher.group(2));
            matcher.appendReplacement(expanded, java.util.regex.Matcher.quoteReplacement(expandIncludes(include)));
        }
        matcher.appendTail(expanded);
        return expanded.toString();
    }

    private static String compile(String name, String source) {
        long compiler = shaderc_compiler_initialize();
        long options = shaderc_compile_options_initialize();
        assertNotEquals(0, compiler);
        assertNotEquals(0, options);
        long result = 0;
        try {
            // Same target and uniform binding policy as Mojang 26.3 GlslCompiler.
            shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
            shaderc_compile_options_set_auto_bind_uniforms(options, true);
            shaderc_compile_options_set_preserve_bindings(options, false);
            shaderc_compile_options_add_macro_definition(options, "X7_SKINNED_MAX_BONES", "64");
            shaderc_compile_options_add_macro_definition(options, "X7_STATIC_MAX_INSTANCES", "16");
            result = shaderc_compile_into_spv(compiler, source,
                    name.endsWith(".vsh") ? shaderc_glsl_vertex_shader : shaderc_glsl_fragment_shader,
                    name, "main", options);
            assertNotEquals(0, result);
            if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
                return shaderc_result_get_error_message(result);
            }
            assertTrue(shaderc_result_get_length(result) > 0);
            return "";
        } finally {
            if (result != 0) shaderc_result_release(result);
            shaderc_compile_options_release(options);
            shaderc_compiler_release(compiler);
        }
    }
}
