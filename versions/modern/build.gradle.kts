import java.util.zip.ZipFile
import java.util.Properties

plugins {
    java
    id("net.fabricmc.fabric-loom") apply false
    id("net.fabricmc.fabric-loom-remap") apply false
}

val minecraftVersion = providers.gradleProperty("minecraft_version").get()
val targets = mapOf(
    "1.21.9" to "0.134.1+1.21.9",
    "1.21.10" to "0.138.4+1.21.10",
    "1.21.11" to "0.141.6+1.21.11",
    "26.1" to "0.145.1+26.1",
    "26.1.1" to "0.145.4+26.1.1",
    "26.1.2" to "0.155.3+26.1.2",
    "26.2" to "0.160.0+26.2",
    "26.3" to "0.161.0+26.3",
)
val fabricVersion = targets[minecraftVersion] ?: error("Unsupported Minecraft target: $minecraftVersion")
val javaVersion = if (minecraftVersion.startsWith("1.21.")) 21 else 25
val obfuscated = javaVersion == 21
apply(plugin = if (obfuscated) "net.fabricmc.fabric-loom-remap" else "net.fabricmc.fabric-loom")
repositories.withType<org.gradle.api.artifacts.repositories.MavenArtifactRepository>().configureEach {
    if (url.host == "maven.fabricmc.net") {
        content { includeGroupByRegex("net\\.fabricmc(\\..*)?") }
    }
}

// Loom adds project repositories, which take precedence over settings repositories.
// Use the verified optional transport cache exclusively only when its target root POM exists.
val fabricMirror = file("build/fabric-maven")
if (fabricMirror.resolve("net/fabricmc/fabric-api/fabric-api/$fabricVersion/fabric-api-$fabricVersion.pom").isFile) {
    repositories.exclusiveContent {
        forRepository { repositories.maven(fabricMirror) }
        filter { includeGroup("net.fabricmc.fabric-api") }
    }
}

group = "com.liy.blendlib"
version = "1.0.0-beta.3+$minecraftVersion"
base.archivesName.set("blendlib-fabric")
layout.buildDirectory.set(layout.projectDirectory.dir("build/$minecraftVersion"))
val repository = rootDir.resolve("../..").canonicalFile

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(javaVersion))
    withSourcesJar()
}

configure<net.fabricmc.loom.api.LoomGradleExtensionAPI> {
    splitEnvironmentSourceSets()
    mods {
        create("blendlib") {
            sourceSet(sourceSets["main"])
            sourceSet(sourceSets["client"])
        }
    }
    runs {
        named("client") { runDir("run/$minecraftVersion/client") }
        named("server") { runDir("run/$minecraftVersion/server") }
    }
}

dependencies {
    add("minecraft", "com.mojang:minecraft:$minecraftVersion")
    if (obfuscated) {
        add("mappings", project.extensions.getByType<net.fabricmc.loom.api.LoomGradleExtensionAPI>().officialMojangMappings())
    }
    add(if (obfuscated) "modImplementation" else "implementation", "net.fabricmc:fabric-loader:${providers.gradleProperty("loader_version").get()}")
    add(if (obfuscated) "modImplementation" else "implementation", "net.fabricmc.fabric-api:fabric-api:$fabricVersion")
    if (minecraftVersion == "26.3") testImplementation("net.fabricmc:fabric-loader-junit:${providers.gradleProperty("loader_version").get()}")
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val sourceModules = listOf("blendlib-api", "blendlib-core", "blendlib-fabric-common", "blendlib-fabric-client")
val preparePortSources = tasks.register("preparePortSources") {
    val output = layout.buildDirectory.dir("generated/sources")
    sourceModules.forEach { inputs.dir(repository.resolve("$it/src/main/java")) }
    inputs.dir(repository.resolve("blendlib-fabric-client/src/client/java"))
    inputs.files(fileTree("overrides"))
    inputs.file("runtime-pins.properties")
    inputs.property("minecraftVersion", minecraftVersion)
    outputs.dir(output)
    doLast {
        val outputDir = output.get().asFile
        val runtimePins = Properties().apply { file("runtime-pins.properties").inputStream().use { load(it) } }
        check(outputDir.canonicalFile.toPath().startsWith(layout.buildDirectory.get().asFile.canonicalFile.toPath()))
        delete(outputDir)
        fun copyJava(source: File, destination: File) {
            source.walkTopDown().filter { it.isFile && it.extension == "java" }.forEach { original ->
                val target = destination.resolve(original.relativeTo(source))
                target.parentFile.mkdirs()
                var text = original.readText()
                if (obfuscated) {
                    text = text.replace("ServerLevelEvents", "ServerWorldEvents")
                        .replace("ServerTickEvents.END_LEVEL_TICK", "ServerTickEvents.END_WORLD_TICK")
                        .replace("PayloadTypeRegistry.clientboundPlay()", "PayloadTypeRegistry.playS2C()")
                        .replace("rendering.v1.level", "rendering.v1.world")
                        .replace("LevelRenderContext", "WorldRenderContext")
                        .replace("LevelRenderEvents", "WorldRenderEvents")
                        .replace("WorldRenderEvents.AfterSolidFeatures", "WorldRenderEvents.AfterEntities")
                        .replace("WorldRenderEvents.AFTER_SOLID_FEATURES", "WorldRenderEvents.AFTER_ENTITIES")
                        .replace("WorldRenderEvents.BeforeTranslucentTerrain", "WorldRenderEvents.BeforeTranslucent")
                        .replace("WorldRenderEvents.BEFORE_TRANSLUCENT_TERRAIN", "WorldRenderEvents.BEFORE_TRANSLUCENT")
                        .replace("renderer.state.level.CameraRenderState", "renderer.state.CameraRenderState")
                        .replace("ClientCommands", "ClientCommandManager")
                        .replace("SimpleReloadListener", "SimpleResourceReloader")
                        .replace(".registerReloadListener(", ".registerReloader(")
                        .replace("import com.mojang.blaze3d.pipeline.DepthStencilState;", "import com.mojang.blaze3d.platform.DepthTestFunction;")
                        .replace(".withDepthStencilState(DepthStencilState.DEFAULT)", ".withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST).withDepthWrite(true)")
                        .replace("RenderTypes.entityCutoutCull(texture)", "RenderTypes.entityCutout(texture)")
                        .replace("RenderTypes.entityCutout(texture, false)", "RenderTypes.entityCutoutNoCull(texture, false)")
                        .replace(".gameRenderer.levelLightmap()", ".gameRenderer.lightTexture().getTextureView()")
                        .replace("VertexFormatElement.Type.FLOAT, false, 3", "VertexFormatElement.Type.FLOAT, VertexFormatElement.Usage.NORMAL, 3")
                    if (original.name == "BlendLibItemSpecialRenderer.java") {
                        text = text.replace("SpecialModelRenderer.Unbaked<BlendLibItemRenderArgument>", "SpecialModelRenderer.Unbaked")
                            .replace("BlendLibItemRenderArgument argument,\n            PoseStack", "BlendLibItemRenderArgument argument,\n            net.minecraft.world.item.ItemDisplayContext displayContext,\n            PoseStack")
                    }
                    if (original.name == "BlendLibItemModelBindings.java") {
                        text = text.replace("binding.baseModelId(), Optional.empty(),", "binding.baseModelId(),")
                    }
                    if (original.name == "Minecraft2612FinalPresentMixin.java") {
                        text = text.replace("renderFrame(Z)V", "runTick(Z)V")
                            .replace("Lcom/mojang/blaze3d/systems/RenderSystem;flipFrame(Lcom/mojang/blaze3d/TracyFrameCapture;)V", "Lcom/mojang/blaze3d/platform/Window;updateDisplay(Lcom/mojang/blaze3d/TracyFrameCapture;)V")
                    }
                    if (original.name == "Minecraft2612OwnedFinalFenceAdapter.java") {
                        text = text.replace("\"/net/minecraft/client/Minecraft.class\"", "\"/\" + Minecraft.class.getName().replace('.', '/') + \".class\"")
                        val productionPin = runtimePins.getProperty("$minecraftVersion.production")
                            ?: error("Missing verified production Minecraft class pin for $minecraftVersion")
                        val developmentPin = runtimePins.getProperty("$minecraftVersion.development")
                            ?: error("Missing verified development Minecraft class pin for $minecraftVersion")
                        text = text.replace("ac3890b42c594a1ff7bf3c3ec945c85cccbd8cb076711d55a20181ed3dd99a7a", productionPin)
                            .replace("85aace61cced0d3388e3c53f8ced84096031d1b94e5cde662f00cc90f1e28d7c", developmentPin)
                    }
                    if (minecraftVersion != "1.21.11") {
                        text = text.replace(Regex("\\bIdentifier\\b"), "ResourceLocation")
                            .replace(".dimension().identifier()", ".dimension().location()")
                            .replace("renderer.rendertype.RenderTypes", "renderer.RenderType")
                            .replace("renderer.rendertype.RenderType", "renderer.RenderType")
                            .replace("RenderTypes.", "RenderType.")
                            .replace(".setLineWidth(MARKER_LINE_WIDTH)", "")
                        if (original.name == "BlendLibItemSpecialRenderer.java") {
                            text = text.replace("Consumer<Vector3fc> output", "java.util.Set<Vector3f> output")
                                .replace("output.accept(", "output.add(")
                        }
                        if (original.name in setOf("StaticDirectDrawExecutor.java", "X7Minecraft2612SkinnedPassSubmitter.java")) {
                            // These unreleased direct-GPU prototypes require independently owned samplers.
                            // 1.21.9/10 only expose mutable texture sampler state, so fail before native work.
                            // The released CPU renderer and all its animation/material APIs stay available.
                            text = text.replace("import com.mojang.blaze3d.textures.GpuSampler;", "")
                                .replace("GpuSampler", "AutoCloseable")
                                .replace(Regex("lightmapSampler = Objects.requireNonNull\\(device.createSampler\\([\\s\\S]*?sampler\"\\);"), "lightmapSampler = unsupportedIndependentSampler();")
                                .replace("pass.bindTexture(\"Sampler0\", texture.getTextureView(), texture.getSampler())", "pass.bindSampler(\"Sampler0\", texture.getTextureView())")
                                .replace("pass.bindTexture(\"Sampler2\", Minecraft.getInstance().gameRenderer.lightTexture().getTextureView(), lightmapSampler)", "pass.bindSampler(\"Sampler2\", Minecraft.getInstance().gameRenderer.lightTexture().getTextureView())")
                                .replace("CommandEncoder encoder =", "unsupportedIndependentSampler();\n            CommandEncoder encoder =")
                            val helper = "\n    private static AutoCloseable unsupportedIndependentSampler() {\n        throw new UnsupportedOperationException(\"Minecraft $minecraftVersion has no independently owned GPU sampler API; use BlendLib's CPU renderer\");\n    }\n"
                            text = text.substringBeforeLast("}") + helper + "}\n"
                        }
                    }
                }
                if (minecraftVersion in setOf("26.2", "26.3")) {
                    text = text.replace(".getMainRenderTarget()", ".gameRenderer.mainRenderTarget()")
                        .replace("pass.setVertexBuffer(0, vertex)", "pass.setVertexBuffer(0, vertex.slice())")
                        .replace("VertexFormat.IndexType.INT", "com.mojang.blaze3d.IndexType.INT")
                        // Mojang 26.2 reordered the draw arguments and added firstInstance.
                        .replace("pass::drawIndexed", "(drawBase, drawFirst, drawCount, drawInstances) -> pass.drawIndexed(drawCount, drawInstances, drawFirst, drawBase, 0)")
                        .replace("Lcom/mojang/blaze3d/systems/RenderSystem;flipFrame(Lcom/mojang/blaze3d/TracyFrameCapture;)V", "Lcom/mojang/blaze3d/systems/GpuSurface;present()V")
                    if (original.name in setOf("StaticDirectDrawExecutor.java", "X7Minecraft2612SkinnedPassSubmitter.java")) {
                        text = text.replace("OptionalInt.empty()", "java.util.Optional.empty()")
                    }
                    if (original.name == "StaticDirectDrawExecutor.java") {
                        text = text.replace("issueIndexedDraw((drawBase", "RenderPass currentPass = pass;\n                issueIndexedDraw((drawBase")
                            .replace("-> pass.drawIndexed(drawCount", "-> currentPass.drawIndexed(drawCount")
                    }
                    if (original.name == "X7Minecraft2612SkinnedPassSubmitter.java") {
                        text = text.replace("IndexedDraw indexedDraw = (drawBase", "RenderPass currentPass = pass;\n                IndexedDraw indexedDraw = (drawBase")
                            .replace("-> pass.drawIndexed(drawCount", "-> currentPass.drawIndexed(drawCount")
                    }
                    if (original.name == "Minecraft2612OwnedFinalFenceAdapter.java") {
                        // Pins come from the verified official 26.2 client and this Loom/Fabric processed class.
                        text = text.replace("ac3890b42c594a1ff7bf3c3ec945c85cccbd8cb076711d55a20181ed3dd99a7a", "9684f0e0874bc61ad47dd242c1f18482810a51dfa65a48fc27d129fbc69d31bb")
                            .replace("85aace61cced0d3388e3c53f8ced84096031d1b94e5cde662f00cc90f1e28d7c", "3af54c47826599bb45397e6c2995abfcc46f0dc985ba17210fe8b4edc70bc8f0")
                            .replace("GpuFence fence = RenderSystem.getDevice().createCommandEncoder().createFence();", "var encoder = RenderSystem.getDevice().createCommandEncoder();\n        GpuFence fence = encoder.createFence();\n        encoder.submit();")
                    }
                }
                if (minecraftVersion == "26.3") {
                    // Verified against Mojang 26.3's RenderPearl API. Keep the baseline source intact.
                    text = text
                        .replace("com.mojang.blaze3d.GpuFormat", "com.mojang.renderpearl.api.GpuFormat")
                        .replace("com.mojang.blaze3d.IndexType", "com.mojang.renderpearl.api.pipeline.IndexType")
                        .replace("com.mojang.blaze3d.PrimitiveTopology", "com.mojang.renderpearl.api.pipeline.PrimitiveTopology")
                        .replace("com.mojang.blaze3d.buffers.GpuBuffer", "com.mojang.renderpearl.api.buffers.GpuBuffer")
                        .replace("com.mojang.blaze3d.buffers.GpuFence", "com.mojang.renderpearl.api.commands.GpuFence")
                        .replace("com.mojang.blaze3d.pipeline.BindGroupLayout", "com.mojang.renderpearl.api.pipeline.BindGroupLayout")
                        .replace("com.mojang.blaze3d.pipeline.DepthStencilState", "com.mojang.renderpearl.api.pipeline.DepthStencilState")
                        .replace("com.mojang.blaze3d.pipeline.RenderPipeline", "com.mojang.renderpearl.api.pipeline.RenderPipeline")
                        .replace("com.mojang.blaze3d.shaders.UniformType", "com.mojang.renderpearl.api.pipeline.UniformType")
                        .replace("com.mojang.blaze3d.systems.CommandEncoder", "com.mojang.renderpearl.api.commands.CommandEncoder")
                        .replace("com.mojang.blaze3d.systems.GpuDevice", "com.mojang.renderpearl.api.device.GpuDevice")
                        .replace("com.mojang.blaze3d.systems.RenderPass", "com.mojang.renderpearl.api.commands.RenderPass")
                        .replace("com.mojang.blaze3d.textures.AddressMode", "com.mojang.renderpearl.api.textures.AddressMode")
                        .replace("com.mojang.blaze3d.textures.FilterMode", "com.mojang.renderpearl.api.textures.FilterMode")
                        .replace("com.mojang.blaze3d.textures.GpuSampler", "com.mojang.renderpearl.api.textures.GpuSampler")
                        .replace("com.mojang.blaze3d.textures.GpuTextureView", "com.mojang.renderpearl.api.textures.GpuTextureView")
                        .replace("com.mojang.blaze3d.vertex.VertexFormat", "com.mojang.renderpearl.api.vertex.VertexFormat")
                        .replace("com.mojang.blaze3d.vertex.VertexFormatElement", "com.mojang.renderpearl.api.vertex.VertexFormatElement")
                        .replace("getBoundingBoxForCulling(E entity)", "getBoundingBoxForCulling(E entity, float partialTick)")
                        .replace("super.getBoundingBoxForCulling(checkedEntity)", "super.getBoundingBoxForCulling(checkedEntity, partialTick)")
                        .replace("poseStack.mulPose(new Quaternionf(", "poseStack.rotate(new Quaternionf(")
                        .replace("poseStack.mulPose(scratch.set(", "poseStack.rotate(scratch.set(")
                        .replace("RenderPipelines.getStaticPipelines()", "RenderPipelines.requiredPipelines()")
                        .replace("9684f0e0874bc61ad47dd242c1f18482810a51dfa65a48fc27d129fbc69d31bb", runtimePins.getProperty("26.3.production") ?: error("Missing 26.3 production pin"))
                        .replace("3af54c47826599bb45397e6c2995abfcc46f0dc985ba17210fe8b4edc70bc8f0", runtimePins.getProperty("26.3.development") ?: error("Missing 26.3 development pin"))
                        .replace("pass.setPipeline(pipeline)", "pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline))")
                        .replace("pass.bindTexture(", "pass.setUniform(")
                        .replace("Lcom/mojang/blaze3d/systems/GpuSurface;present()V", "Lcom/mojang/renderpearl/api/device/GpuSurface;present()V")
                }
                target.writeText(text)
            }
        }
        sourceModules.forEach { copyJava(repository.resolve("$it/src/main/java"), outputDir.resolve("main")) }
        copyJava(repository.resolve("blendlib-fabric-client/src/client/java"), outputDir.resolve("client"))
        copyJava(file("overrides/$minecraftVersion/client"), outputDir.resolve("client"))
    }
}

sourceSets["main"].java.setSrcDirs(listOf(layout.buildDirectory.dir("generated/sources/main")))
sourceSets["client"].java.setSrcDirs(listOf(layout.buildDirectory.dir("generated/sources/client")))
sourceSets["main"].resources.setSrcDirs(listOf(repository.resolve("blendlib-fabric-client/src/main/resources")))
sourceSets["client"].resources.setSrcDirs(listOf(repository.resolve("blendlib-fabric-client/src/client/resources")))
sourceSets["test"].java.srcDir("tests/$minecraftVersion")
if (obfuscated) sourceSets["test"].java.srcDir("tests/obfuscated")
sourceSets["test"].compileClasspath += sourceSets["client"].output + configurations["clientCompileClasspath"]
sourceSets["test"].runtimeClasspath += sourceSets["client"].output + configurations["clientRuntimeClasspath"]
tasks.test { useJUnitPlatform() }

// Exercise the shipped 26.3 integration against real Minecraft/Fabric types, not only the
// original split-module 26.1.2 classpath. Consumer samples compile as part of this source set.
if (minecraftVersion == "26.3") {
    sourceSets["test"].java.apply {
        srcDir(repository.resolve("blendlib-core/src/test/java"))
        srcDir(repository.resolve("blendlib-fabric-client/src/test/java"))
        srcDir(repository.resolve("blendlib-fabric-consumer-fixture/src/client/java"))
        include(
            "**/X7PipelinePortTest.java",
            "**/Minecraft263ShutdownMixinTest.java",
            "**/StaticDirectPipelinePortTest.java",
            "**/AnimationV2NativeClipTest.java",
            "**/SynchronizedVisualEventCursorTest.java",
            "**/LayerAnimationVisualEventCursorTest.java",
            "**/AnimationControllerTest.java",
            "**/SkinnedAnimationRuntimeTest.java",
            "**/SkinnedAnimationRuntimeSourceBoundaryTest.java",
            "**/EntityLayerCueCacheTest.java",
            "**/*Locomotion*Test.java",
            "**/entity/consumer/LocomotionRulesConsumerSample.java",
            "**/MaterialAppearanceSubmissionTest.java",
            "**/MaterialAppearanceCaptureTest.java",
            "**/*NamedSkin*Test.java",
            "**/BlendLibModelSkinsTest.java",
            "**/NamedSkinConsumerFixture.java",
            "**/ClientAnimationRigViewTestAccess.java",
            "**/animation/runtime/procedural/*.java",
            "**/fabric/client/procedural/*.java",
            "**/entity/BlendEntitySocketsTest.java",
            "**/entity/BlendEntityCullingEnvelopeTest.java",
            "**/entity/PublicEntityConsumerCompileFixture.java",
            "**/entity/BlendEntityAttachmentCompositionTest.java",
            "**/entity/ExampleNestedEntityAttachmentsTest.java",
            "**/NestedAttachmentPreparedGeometryTest.java",
            "**/ClientAnimationPoseModifierPipelineTest.java",
            "**/AttachmentTopologyTest.java",
            "**/entity/BlendEntityMaterialAppearanceTest.java",
            "**/entity/consumer/LayeredAnimationConsumerSample.java",
            "**/item/ItemAnimation*Test.java",
            "**/item/BlendLibItemAdapterContractsTest.java",
            "**/item/consumer/*.java",
        )
    }
    tasks.test {
        systemProperty("blendlib.projectDir", repository.resolve("blendlib-fabric-client").absolutePath)
    }
}

tasks.withType<JavaCompile>().configureEach {
    dependsOn(preparePortSources)
    options.encoding = "UTF-8"
    options.release.set(javaVersion)
}
tasks.withType<ProcessResources>().configureEach {
    inputs.property("portVersion", project.version)
    inputs.property("minecraftVersion", minecraftVersion)
    inputs.property("fabricVersion", fabricVersion)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
        filter { line -> line.replace("26.1.2", minecraftVersion)
            .replace("0.155.3+$minecraftVersion", fabricVersion)
            .replace("\"java\": \"25\"", "\"java\": \">=$javaVersion\"") }
    }
    filesMatching("blendlib.client.mixins.json") {
        filter { line -> line.replace("JAVA_25", "JAVA_$javaVersion") }
    }
    if (minecraftVersion == "26.3") {
        // 26.3 compiles both backends through ShaderC/SPIR-V. Keep earlier targets' resources unchanged.
        // See https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3#shader-compilation-changes
        filesMatching("assets/blendlib/shaders/core/x7_*") {
            val vertexShader = name.endsWith(".vsh")
            val directStatic = name == "x7_static_direct.vsh"
            val varyingLocations = mapOf(
                "sphericalVertexDistance" to 0, "cylindricalVertexDistance" to 1,
                "vertexColor" to 2, "lightMapColor" to 3, "texCoord" to 4,
            )
            filter { line ->
                val declaration = Regex("^(in|out) (\\w+) (\\w+);$").matchEntire(line)
                if (declaration != null) {
                    val (direction, _, variable) = declaration.destructured
                    val location = if (vertexShader && direction == "in") {
                        when (variable) {
                            "Position" -> 0
                            "Normal" -> 1
                            "UV0" -> if (directStatic) 2 else 1
                            else -> error("Unknown 26.3 vertex input: $variable")
                        }
                    } else if (!vertexShader && direction == "out" && variable == "fragColor") 0
                    else varyingLocations[variable] ?: error("Unknown 26.3 shader interface: $variable")
                    "layout(location = $location) $line"
                } else line.replace("#version 330", "#version 330\n#extension GL_ARB_separate_shader_objects : require")
                    .replace("#moj_import", "#include")
                    // Every existing direct draw uses firstInstance = 0, preserving array indexing on both backends.
                    .replace("gl_InstanceID", "gl_InstanceIndex")
                    .replace("gl_VertexID", "gl_VertexIndex")
            }
        }
    }
    if (obfuscated) {
        filesMatching("**/*.vsh") {
            // Matches vanilla 1.21's entity shader lightmap lookup; 26.x's helper does not exist here.
            filter { line -> line.replace("#moj_import <minecraft:sample_lightmap.glsl>",
                "vec4 sample_lightmap(sampler2D lightMap, ivec2 uv) { return texelFetch(lightMap, uv / 16, 0); }") }
        }
    }
}
tasks.withType<Jar>().configureEach {
    from(repository.resolve("LICENSE")) { into("META-INF") }
    from(repository.resolve("NOTICE")) { into("META-INF") }
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    manifest.attributes("BlendLib-Minecraft-Target" to minecraftVersion, "Implementation-Version" to project.version)
}
tasks.named("sourcesJar") { dependsOn(preparePortSources) }

tasks.register("verifyRuntimeJar") {
    dependsOn(if (obfuscated) "remapJar" else "jar")
    doLast {
        val artifact = layout.buildDirectory.file("libs/blendlib-fabric-${project.version}.jar").get().asFile
        ZipFile(artifact).use { zip ->
            val metadata = zip.getInputStream(zip.getEntry("fabric.mod.json")).reader().readText()
            check(metadata.contains("\"minecraft\": \"$minecraftVersion\""))
            check(metadata.contains(project.version.toString()))
            check(metadata.contains("\"fabricloader\": \">=${providers.gradleProperty("loader_version").get()}\""))
            check(metadata.contains("\"fabric-api\": \">=$fabricVersion\""))
            listOf("api/BlendResourceId", "core/BlendCoreService", "fabric/common/BlendLibCommonEntrypoint", "fabric/client/BlendLibClientEntrypoint").forEach {
                check(zip.getEntry("com/liy/blendlib/$it.class") != null) { "Missing runtime class: $it" }
            }
            sourceModules.forEach { module ->
                listOf("main", "client").forEach { side ->
                    val source = repository.resolve("$module/src/$side/java")
                    source.walkTopDown().filter {
                        it.isFile && it.extension == "java" && it.nameWithoutExtension !in setOf("package-info", "module-info")
                    }.forEach { original ->
                        val classPath = original.relativeTo(source).invariantSeparatorsPath.removeSuffix(".java") + ".class"
                        check(zip.getEntry(classPath) != null) { "Baseline class missing from runtime: $classPath" }
                    }
                }
            }
            zip.entries().asSequence().filter { it.name.endsWith(".class") }.forEach { entry ->
                val header = zip.getInputStream(entry).use { it.readNBytes(8) }
                val major = (header[6].toInt() and 255) * 256 + (header[7].toInt() and 255)
                check(major <= javaVersion + 44) { "Wrong Java class version: ${entry.name}: $major" }
            }
        }
    }
}
tasks.named("check") { dependsOn("verifyRuntimeJar") }

// An explicitly enabled consumer mod, never part of BlendLib's production source sets or JAR.
// Keeping it here allows compilation against the exact 26.3 port rather than the 26.1.2 root build.
if (providers.gradleProperty("runnable_examples").orNull == "true") {
    check(minecraftVersion == "26.3") { "Runnable examples currently target only Minecraft 26.3" }
    val examples = sourceSets.create("runnableExamples")
    val examplesClient = sourceSets.create("runnableExamplesClient")
    examples.java.setSrcDirs(listOf("showcase/src/main/java"))
    examples.resources.setSrcDirs(listOf("showcase/src/main/resources"))
    examplesClient.java.setSrcDirs(listOf("showcase/src/client/java"))
    examplesClient.resources.setSrcDirs(emptyList<String>())
    examples.compileClasspath += sourceSets["main"].output + configurations["compileClasspath"]
    examples.runtimeClasspath += sourceSets["main"].output + configurations["runtimeClasspath"]
    examplesClient.compileClasspath += examples.output + sourceSets["main"].output +
            sourceSets["client"].output + configurations["clientCompileClasspath"]
    examplesClient.runtimeClasspath += examples.output + sourceSets["main"].output +
            sourceSets["client"].output + configurations["clientRuntimeClasspath"]

    val sourceAssets = repository.resolve("blendlib-showcase/src/main/resources/assets/blendlib_showcase")
    // The binaries already belong to this repository (Apache-2.0). No network, Blender, Python,
    // external asset path or optional benchmark pack is needed to build the runnable consumer.
    val assetCopies = mapOf(
        "models3d/showcase_animation/showcase_actor.glb" to "models3d/actor.glb",
        "models3d/fixtures/static_model.glb" to "models3d/marker.glb",
        "textures/blendlib/showcase_animation/showcase_actor__showcaseanimationsurface.png" to "textures/actor.png",
        "textures/blendlib/fixtures_static_model__staticsurface.png" to "textures/marker.png",
    )
    val prepareExampleAssets = tasks.register<Sync>("prepareRunnableExampleAssets") {
        into(layout.buildDirectory.dir("generated/runnable-example-resources/assets/blendlib_runnable_examples"))
        assetCopies.forEach { (source, destination) ->
            from(sourceAssets.resolve(source)) {
                into(destination.substringBeforeLast('/'))
                rename { destination.substringAfterLast('/') }
            }
        }
    }
    examples.resources.srcDir(layout.buildDirectory.dir("generated/runnable-example-resources"))
    tasks.named(examples.processResourcesTaskName) { dependsOn(prepareExampleAssets) }

    configure<net.fabricmc.loom.api.LoomGradleExtensionAPI> {
        mods {
            create("blendlib_runnable_examples") {
                sourceSet(examples)
                sourceSet(examplesClient)
            }
        }
        runs {
            create("runnableExamplesClient") {
                client()
                source(examplesClient)
                setConfigName("BlendLib 26.3 Runnable Examples (opt-in)")
                runDir("run/26.3/runnable-examples-client")
            }
        }
    }
    val exampleJar = tasks.register<Jar>("runnableExamplesJar") {
        group = "build"
        description = "Builds the separate opt-in Minecraft 26.3 consumer example mod."
        archiveBaseName.set("blendlib-runnable-examples")
        from(examples.output)
        from(examplesClient.output)
    }
    val examplesVerify = sourceSets.create("runnableExamplesVerify")
    examplesVerify.java.setSrcDirs(listOf("showcase/src/verification/java"))
    examplesVerify.resources.setSrcDirs(emptyList<String>())
    examplesVerify.compileClasspath += examplesClient.output + examplesClient.compileClasspath
    examplesVerify.runtimeClasspath += sourceSets["main"].output + sourceSets["client"].output +
            files(exampleJar.flatMap { it.archiveFile }) + configurations["clientRuntimeClasspath"]
    val verifyExampleAssets = tasks.register<JavaExec>("verifyRunnableExampleAssets") {
        group = "verification"
        description = "Loads packaged example GLBs and evaluates the actual layered/procedural scene headlessly."
        dependsOn(exampleJar)
        classpath = examplesVerify.runtimeClasspath
        mainClass.set("com.liy.blendlib.fabric.client.animation.runtime.RunnableExampleAssetVerification")
        javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    }
    tasks.register("verifyRunnableExamples") {
        group = "verification"
        description = "Compiles and checks the opt-in consumer JAR, copied assets and runtime isolation."
        dependsOn(exampleJar, tasks.named("jar"), verifyExampleAssets)
        doLast {
            val namespace = "assets/blendlib_runnable_examples/"
            ZipFile(exampleJar.get().archiveFile.get().asFile).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toSet()
                check(names.none { it.startsWith("com/liy/blendlib/fabric/") ||
                        it.startsWith("com/liy/blendlib/core/") || it.startsWith("com/liy/blendlib/api/") }) {
                    "Example JAR must not embed library implementation or API classes"
                }
                listOf("ExampleContent", "LayeredActor", "ExampleClient", "ExampleItemCommands", "ExampleAnimationScene", "ExampleInspectionCommands", "ExampleLayerInspection", "ExampleItemInspection", "ExampleLayerVisualEvents", "ExampleMaterialAppearance", "ExampleItemMaterialAppearance", "ExampleNamedSkins", "ExampleAttachmentScene", "ExampleAttachmentOwners", "ExampleTwoBoneIkScene", "ExampleLocomotionScene").forEach {
                    check("com/liy/blendlib/examples/runnable/$it.class" in names) { "Missing example class: $it" }
                }
                val metadata = zip.getInputStream(zip.getEntry("fabric.mod.json")).reader().readText()
                check(metadata.contains("\"id\": \"blendlib_runnable_examples\""))
                check(metadata.contains("\"minecraft\": \"26.3\""))
                check(metadata.contains("\"blendlib\": \"${project.version}\""))
                check(!metadata.contains("\"mixins\""))
                listOf("ExampleContent", "LayeredActor", "ExampleLayerVisualEvents").forEach {
                    val bytes = zip.getInputStream(zip.getEntry("com/liy/blendlib/examples/runnable/$it.class")).readBytes()
                    check(!bytes.toString(Charsets.ISO_8859_1).contains("net/minecraft/client/")) {
                        "Common example entrypoint/entity must remain server-safe: $it"
                    }
                }
                assetCopies.forEach { (source, destination) ->
                    val entry = zip.getEntry(namespace + destination)
                    check(entry != null) { "Missing example asset: $destination" }
                    check(zip.getInputStream(entry).readBytes().contentEquals(sourceAssets.resolve(source).readBytes())) {
                        "Example binary must be the documented repository-local asset: $destination"
                    }
                }
                check(namespace + "blend_animation_rules/locomotion_actor.json" in names) {
                    "Missing packaged locomotion sidecar"
                }
                val slurper = groovy.json.JsonSlurper()
                listOf("actor", "appearance_actor", "wand", "appearance_wand", "marker",
                        "mechanical_arm", "ik_target_marker", "ik_end_marker", "locomotion_actor").forEach { model ->
                    val path = namespace + "blend_models/$model.json"
                    val descriptor = slurper.parseText(zip.getInputStream(zip.getEntry(path)).reader().readText()) as Map<*, *>
                    val mesh = descriptor["mesh"] as String
                    check(namespace + mesh.substringAfter(':') in names) { "Unresolved example mesh: $mesh" }
                    val materials = descriptor["materials"] as Map<*, *>
                    materials.values.forEach { value ->
                        val texture = (value as Map<*, *>)["base_color"] as String
                        check(namespace + texture.substringAfter(':') in names) { "Unresolved example texture: $texture" }
                    }
                    if (model == "mechanical_arm") {
                        val animation = descriptor["animation"] as Map<*, *>
                        check((animation["states"] as Map<*, *>).containsKey("blendlib_runnable_examples:idle"))
                        check((descriptor["sockets"] as Map<*, *>).keys.containsAll(listOf(
                                "blendlib_runnable_examples:ik_end", "blendlib_runnable_examples:ik_origin")))
                    } else if (model !in setOf("marker", "ik_target_marker", "ik_end_marker")) {
                        val animation = descriptor["animation"] as Map<*, *>
                        val states = animation["states"] as Map<*, *>
                        check(states.keys.containsAll(listOf("idle", "walk", "attack").map { "blendlib_runnable_examples:$it" }))
                        check((descriptor["sockets"] as Map<*, *>).containsKey("blendlib_runnable_examples:tip"))
                    }
                }
                listOf("models3d/mechanical_arm.glb", "models3d/ik_target_marker.glb",
                        "models3d/ik_end_marker.glb", "textures/mechanical_arm.png").forEach { asset ->
                    val bytes = zip.getInputStream(zip.getEntry(namespace + asset)).readBytes()
                    check(bytes.contentEquals(file("showcase/src/main/resources/$namespace$asset").readBytes())) {
                        "Mechanical-arm asset must match the committed authored resource: $asset"
                    }
                }
                listOf("ember", "frost").forEach { skin ->
                    val texture = "textures/skins/$skin.png"
                    val bytes = zip.getInputStream(zip.getEntry(namespace + texture)).readBytes()
                    check(bytes.contentEquals(file("showcase/src/main/resources/$namespace$texture").readBytes())) {
                        "Named skin texture must be the committed authored PNG: $texture"
                    }
                }
                check("data/blendlib_runnable_examples/function/named_skins.mcfunction" in names)
                check("data/blendlib_runnable_examples/function/item_appearance.mcfunction" in names)
                check(namespace + "items/animated_wand.json" in names)
                check(namespace + "models/item/animated_wand.json" in names)
            }
            ZipFile(tasks.named<Jar>("jar").get().archiveFile.get().asFile).use { zip ->
                check(zip.entries().asSequence().none {
                    it.name.startsWith("com/liy/blendlib/examples/runnable/") || it.name.startsWith(namespace) ||
                            it.name.startsWith("data/blendlib_runnable_examples/")
                }) { "The normal BlendLib runtime must not include opt-in example content" }
                val metadata = zip.getInputStream(zip.getEntry("fabric.mod.json")).reader().readText()
                check(!metadata.contains("blendlib_runnable_examples"))
            }
        }
    }
}
