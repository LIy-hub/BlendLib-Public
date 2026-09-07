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
    "26.1.2" to "0.154.2+26.1.2",
    "26.2" to "0.153.0+26.2",
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
version = "1.0.0-beta.2+$minecraftVersion"
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
                if (minecraftVersion == "26.2") {
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
            .replace("0.154.2+$minecraftVersion", fabricVersion)
            .replace("\"java\": \"25\"", "\"java\": \">=$javaVersion\"") }
    }
    filesMatching("blendlib.client.mixins.json") {
        filter { line -> line.replace("JAVA_25", "JAVA_$javaVersion") }
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
