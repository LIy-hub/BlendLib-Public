import java.nio.charset.StandardCharsets
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.tasks.Sync

plugins {
    id("net.fabricmc.fabric-loom")
}

val minecraftVersion = providers.gradleProperty("minecraft_version").get()
val loaderVersion = providers.gradleProperty("loader_version").get()
val fabricVersion = providers.gradleProperty("fabric_version").get()

loom {
    splitEnvironmentSourceSets()

    mods {
        create("blendlib_showcase") {
            sourceSet(sourceSets["main"])
            sourceSet(sourceSets["client"])
        }
    }

    runs {
        named("client") {
            setConfigName("BlendLib Showcase Client")
            runDir("run/client")
        }
        named("server") {
            setConfigName("BlendLib Showcase Server")
            runDir("run/server")
        }
        // P3/P4/P5/P7 server smokes must not inherit the generic development server's
        // wildcard socket or `world` directory.  These opt-in local-only profiles remain
        // deliberately separate from the default `server` run and from the P6/P8 harnesses.
        create("p3SmokeServer") {
            server()
            setConfigName("BlendLib Showcase P3 Smoke Server (loopback isolated)")
            runDir("run/p3-smoke-server")
        }
        create("p4SmokeServer") {
            server()
            setConfigName("BlendLib Showcase P4 Smoke Server (loopback isolated)")
            runDir("run/p4-smoke-server")
        }
        create("p5SmokeServer") {
            server()
            setConfigName("BlendLib Showcase P5 Smoke Server (loopback isolated)")
            runDir("run/p5-smoke-server")
        }
        create("p7SmokeServer") {
            server()
            setConfigName("BlendLib Showcase P7 Smoke Server (loopback isolated)")
            runDir("run/p7-smoke-server")
        }
        // P6 synchronization evidence must never share a normal Showcase profile, world, or
        // server socket. These opt-in configurations are local-only test harnesses; they do not
        // change the normal client/server runs or any published artifact.
        create("p6SyncServer") {
            server()
            setConfigName("BlendLib Showcase P6 Sync Server (loopback isolated)")
            runDir("run/p6-sync-server")
        }
        create("p6ClientA") {
            client()
            setConfigName("BlendLib Showcase P6 Client A (isolated)")
            runDir("run/p6-client-a")
            programArgs("--username", "BlendLibP6A")
        }
        create("p6ClientB") {
            client()
            setConfigName("BlendLib Showcase P6 Client B (isolated)")
            runDir("run/p6-client-b")
            programArgs("--username", "BlendLibP6B")
        }
        // P8 re-smoke runs are intentionally separate from both the normal development profiles
        // and the P6 synchronization harness.  They exercise workspace wiring only; the evidence
        // procedure binds that observation to SHA256SUMS without claiming an external JAR install.
        create("p8CurrentManifestShowcaseServer") {
            server()
            setConfigName("BlendLib Showcase P8 Current-Manifest Server (loopback isolated)")
            runDir("run/p8-current-manifest-server")
        }
        create("p8CurrentManifestShowcaseClient") {
            client()
            setConfigName("BlendLib Showcase P8 Current-Manifest Client (isolated)")
            runDir("run/p8-current-manifest-client")
            programArgs("--username", "BlendLibP8Smoke")
        }
        create("p7BenchmarkClient") {
            client()
            setConfigName("BlendLib Showcase P7 Benchmark Client (isolated)")
            runDir("run/p7-benchmark")
            property("blendlib.showcase.p7.enabled", "true")
            property(
                "blendlib.showcase.p7.output",
                layout.projectDirectory.dir("run/p7-benchmark/benchmark-results").asFile.absolutePath,
            )
        }
        create("p7IrisSodiumSmokeClient") {
            client()
            setConfigName("BlendLib Showcase P7 Iris/Sodium Smoke Client (isolated)")
            runDir("run/p7-iris-sodium")
            // This marker is documentary/run-local only. It neither enables the P7 benchmark
            // scene nor changes any renderer, material, culling, or measurement behavior.
            property("blendlib.showcase.p7.iris_sodium_smoke", "true")
        }
    }
}

dependencies {
    implementation(project(":blendlib-api"))
    // Main/server sources may use the public server animation facade, but never a client, core,
    // renderer, resource-loader, or implementation package. The runtime already receives common
    // through the explicit BlendLib adapter variant below.
    compileOnly(project(":blendlib-fabric-common"))
    // Client sources receive the version-specific public adapter through explicit JAR variants
    // that never extend main runtime.
    add("clientCompileOnly", project(mapOf(
        "path" to ":blendlib-fabric-client",
        "configuration" to "clientAdapterApiElements",
    )))
    add("clientRuntimeOnly", project(mapOf(
        "path" to ":blendlib-fabric-client",
        "configuration" to "clientAdapterRuntimeElements",
    )))
    // Fabric Loader must see BlendLib's mod metadata and server-safe common entrypoint at runtime.
    // runtimeOnly keeps the implementation out of Showcase main/server source compilation.
    runtimeOnly(project(mapOf(
        "path" to ":blendlib-fabric-client",
        "configuration" to "runtimeElements",
    )))

    minecraft("com.mojang:minecraft:$minecraftVersion")
    // Minecraft 26.1.2 is already distributed in the official Mojang namespace.
    // Loom 1.15.5 runs it in non-obfuscated mode and rejects a second mappings layer.
    implementation("net.fabricmc:fabric-loader:$loaderVersion")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricVersion")
}

val verifyShowcaseDependencyBoundary = tasks.register("verifyShowcaseDependencyBoundary") {
    group = "verification"
    description = "Verifies Showcase main/client source-set dependency boundaries."

    doLast {
        fun projectPaths(configurationName: String): Set<String> =
            configurations.named(configurationName).get().incoming.resolutionResult.allComponents
                .mapNotNull { component ->
                    (component.id as? ProjectComponentIdentifier)?.projectPath
                }
                .toSet()

        fun publishedBlendLibModules(configurationName: String): Set<String> =
            configurations.named(configurationName).get().incoming.resolutionResult.allComponents
                .mapNotNull { component ->
                    (component.id as? ModuleComponentIdentifier)
                        ?.takeIf { it.group == "com.liy.blendlib" }
                        ?.let { "${it.group}:${it.module}" }
                }
                .toSet()

        val compileProjects = projectPaths("compileClasspath")
        val allowedCompileProjects = setOf(project.path, ":blendlib-api", ":blendlib-fabric-common")
        val unexpectedCompileProjects = compileProjects - allowedCompileProjects
        check(unexpectedCompileProjects.isEmpty()) {
            "Showcase compileClasspath exposes non-API BlendLib projects: $unexpectedCompileProjects"
        }
        val requiredCompileProjects = allowedCompileProjects - project.path
        check(requiredCompileProjects.all { it in compileProjects }) {
            "Showcase compileClasspath must include the public API and server facade: $compileProjects"
        }

        val allowedPublishedCompileModules = setOf(
            "com.liy.blendlib:blendlib-api",
            "com.liy.blendlib:blendlib-fabric-common",
        )
        val unexpectedPublishedCompileModules = publishedBlendLibModules("compileClasspath") - allowedPublishedCompileModules
        check(unexpectedPublishedCompileModules.isEmpty()) {
            "Showcase compileClasspath exposes non-API BlendLib modules: $unexpectedPublishedCompileModules"
        }

        val clientCompileProjects = projectPaths("clientCompileClasspath")
        val allowedClientCompileProjects = setOf(
            project.path,
            ":blendlib-api",
            ":blendlib-fabric-common",
            ":blendlib-fabric-client",
        )
        val unexpectedClientCompileProjects = clientCompileProjects - allowedClientCompileProjects
        check(unexpectedClientCompileProjects.isEmpty()) {
            "Showcase clientCompileClasspath exposes non-public BlendLib projects: $unexpectedClientCompileProjects"
        }
        check(":blendlib-api" in clientCompileProjects
                && ":blendlib-fabric-common" in clientCompileProjects
                && ":blendlib-fabric-client" in clientCompileProjects) {
            "Showcase clientCompileClasspath must include the public API, server facade, and 26.1.2 client adapter"
        }

        val unexpectedPublishedClientModules = publishedBlendLibModules("clientCompileClasspath") - setOf(
            "com.liy.blendlib:blendlib-api",
            "com.liy.blendlib:blendlib-fabric-common",
            "com.liy.blendlib:blendlib-fabric-client",
        )
        check(unexpectedPublishedClientModules.isEmpty()) {
            "Showcase clientCompileClasspath exposes non-public BlendLib modules: $unexpectedPublishedClientModules"
        }

        val clientRuntimeProjects = projectPaths("clientRuntimeClasspath")
        val requiredClientRuntimeProjects = setOf(
            ":blendlib-api",
            ":blendlib-core",
            ":blendlib-fabric-common",
            ":blendlib-fabric-client",
        )
        val allowedClientRuntimeProjects = setOf(project.path) + requiredClientRuntimeProjects
        val unexpectedClientRuntimeProjects = clientRuntimeProjects - allowedClientRuntimeProjects
        check(unexpectedClientRuntimeProjects.isEmpty()) {
            "Showcase clientRuntimeClasspath exposes unexpected BlendLib projects: $unexpectedClientRuntimeProjects"
        }
        val missingClientRuntimeProjects = requiredClientRuntimeProjects - clientRuntimeProjects
        check(missingClientRuntimeProjects.isEmpty()) {
            "Showcase clientRuntimeClasspath is missing BlendLib runtime projects: $missingClientRuntimeProjects"
        }

        val allowedPublishedClientRuntimeModules = setOf(
            "com.liy.blendlib:blendlib-api",
            "com.liy.blendlib:blendlib-core",
            "com.liy.blendlib:blendlib-fabric-common",
            "com.liy.blendlib:blendlib-fabric-client",
        )
        val unexpectedPublishedClientRuntimeModules = publishedBlendLibModules("clientRuntimeClasspath") - allowedPublishedClientRuntimeModules
        check(unexpectedPublishedClientRuntimeModules.isEmpty()) {
            "Showcase clientRuntimeClasspath exposes unexpected BlendLib modules: $unexpectedPublishedClientRuntimeModules"
        }

        val runtimeProjects = projectPaths("runtimeClasspath")
        val requiredRuntimeProjects = setOf(
            ":blendlib-api",
            ":blendlib-core",
            ":blendlib-fabric-common",
            ":blendlib-fabric-client",
        )
        val unexpectedRuntimeProjects = runtimeProjects - (setOf(project.path) + requiredRuntimeProjects)
        check(unexpectedRuntimeProjects.isEmpty()) {
            "Showcase runtimeClasspath exposes unexpected BlendLib projects: $unexpectedRuntimeProjects"
        }
        val missingRuntimeProjects = requiredRuntimeProjects - runtimeProjects
        check(missingRuntimeProjects.isEmpty()) {
            "Showcase runtimeClasspath is missing BlendLib server runtime projects: $missingRuntimeProjects"
        }

        val allowedPublishedRuntimeModules = setOf(
            "com.liy.blendlib:blendlib-api",
            "com.liy.blendlib:blendlib-core",
            "com.liy.blendlib:blendlib-fabric-common",
            "com.liy.blendlib:blendlib-fabric-client",
        )
        val unexpectedPublishedRuntimeModules = publishedBlendLibModules("runtimeClasspath") - allowedPublishedRuntimeModules
        check(unexpectedPublishedRuntimeModules.isEmpty()) {
            "Showcase runtimeClasspath exposes unexpected BlendLib modules: $unexpectedPublishedRuntimeModules"
        }
    }
}

tasks.named("check") {
    dependsOn(verifyShowcaseDependencyBoundary)
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

val prepareShowcaseServerRun = tasks.register("prepareShowcaseServerRun") {
    group = "fabric"
    description = "Creates the isolated showcase server EULA acceptance file."
    val eulaFile = layout.projectDirectory.file("run/server/eula.txt")
    outputs.file(eulaFile)

    doLast {
        val destination = eulaFile.asFile
        destination.parentFile.mkdirs()
        destination.writeText("eula=true\n", StandardCharsets.UTF_8)
    }
}

tasks.named("runServer") {
    dependsOn(prepareShowcaseServerRun)
}

val p6SyncServerRunDirectory = layout.projectDirectory.dir("run/p6-sync-server")
val p6SyncServerPropertiesTemplate = rootProject.layout.projectDirectory.file(
    "test-assets/p6-sync-run/server.properties.template",
)
val p6SyncServerEula = p6SyncServerRunDirectory.file("eula.txt")
val p6SyncServerProperties = p6SyncServerRunDirectory.file("server.properties")

val prepareP6SyncServerRun = tasks.register("prepareP6SyncServerRun") {
    group = "fabric"
    description = "Creates the P6 loopback-only server files without touching normal Showcase runs."
    inputs.file(p6SyncServerPropertiesTemplate)
    outputs.file(p6SyncServerEula)
    outputs.file(p6SyncServerProperties)

    doLast {
        val expectedServerProperties = p6SyncServerPropertiesTemplate.asFile.readText(StandardCharsets.UTF_8)

        fun createOnlyIfMissingOrExact(destination: java.io.File, expected: String) {
            destination.parentFile.mkdirs()
            if (destination.isFile) {
                check(destination.readText(StandardCharsets.UTF_8) == expected) {
                    "Refusing to overwrite P6 isolated run file with different contents: $destination. " +
                            "Resolve only that exact P6 run directory before retrying."
                }
                return
            }
            check(!destination.exists()) {
                "P6 isolated run target is not a regular file: $destination"
            }
            destination.writeText(expected, StandardCharsets.UTF_8)
        }

        fun loadProperties(contents: String): Properties {
            return Properties().also { properties ->
                contents.reader().use { reader -> properties.load(reader) }
            }
        }

        fun createP6ServerPropertiesIfMissingOrVerifyRequiredSafety(destination: java.io.File, expected: String) {
            destination.parentFile.mkdirs()
            if (destination.isFile) {
                val expectedP6Properties = loadProperties(expected)
                check(expectedP6Properties.isNotEmpty()) {
                    "P6 server.properties template did not define any safety settings: $p6SyncServerPropertiesTemplate"
                }
                val actualP6Properties = loadProperties(destination.readText(StandardCharsets.UTF_8))
                val mismatches = expectedP6Properties.stringPropertyNames()
                    .sorted()
                    .mapNotNull { key ->
                        val expectedValue = expectedP6Properties.getProperty(key)
                        val actualValue = actualP6Properties.getProperty(key)
                        if (actualValue == expectedValue) {
                            null
                        } else {
                            "$key expected '$expectedValue' but found '${actualValue ?: "<missing>"}'"
                        }
                    }
                check(mismatches.isEmpty()) {
                    "Refusing to use P6 isolated server.properties because required safety setting(s) differ: " +
                            "${mismatches.joinToString("; ")}. Resolve only that exact P6 run directory before retrying; " +
                            "the file was not overwritten."
                }
                return
            }
            check(!destination.exists()) {
                "P6 isolated run target is not a regular file: $destination"
            }
            destination.writeText(expected, StandardCharsets.UTF_8)
        }

        createOnlyIfMissingOrExact(p6SyncServerEula.asFile, "eula=true\n")
        createP6ServerPropertiesIfMissingOrVerifyRequiredSafety(p6SyncServerProperties.asFile, expectedServerProperties)
    }
}

tasks.named("runP6SyncServer") {
    dependsOn(prepareP6SyncServerRun)
}

val p8CurrentManifestShowcaseServerRunDirectory = layout.projectDirectory.dir("run/p8-current-manifest-server")
val p8CurrentManifestShowcaseServerPropertiesTemplate = rootProject.layout.projectDirectory.file(
    "test-assets/p8-smoke-run/showcase-server.properties.template",
)
val p8CurrentManifestShowcaseServerEula = p8CurrentManifestShowcaseServerRunDirectory.file("eula.txt")
val p8CurrentManifestShowcaseServerProperties = p8CurrentManifestShowcaseServerRunDirectory.file("server.properties")

val prepareP8CurrentManifestShowcaseServerRun = tasks.register("prepareP8CurrentManifestShowcaseServerRun") {
    group = "fabric"
    description = "Creates the P8 current-manifest loopback-only Showcase server files without touching normal runs."
    inputs.file(p8CurrentManifestShowcaseServerPropertiesTemplate)
    outputs.file(p8CurrentManifestShowcaseServerEula)
    outputs.file(p8CurrentManifestShowcaseServerProperties)

    doLast {
        val expectedServerProperties = p8CurrentManifestShowcaseServerPropertiesTemplate.asFile.readText(StandardCharsets.UTF_8)

        fun createOnlyIfMissingOrExact(destination: java.io.File, expected: String) {
            destination.parentFile.mkdirs()
            if (destination.isFile) {
                check(destination.readText(StandardCharsets.UTF_8) == expected) {
                    "Refusing to overwrite P8 isolated run file with different contents: $destination. " +
                            "Resolve only that exact P8 run directory before retrying."
                }
                return
            }
            check(!destination.exists()) {
                "P8 isolated run target is not a regular file: $destination"
            }
            destination.writeText(expected, StandardCharsets.UTF_8)
        }

        fun loadProperties(contents: String): Properties {
            return Properties().also { properties ->
                contents.reader().use { reader -> properties.load(reader) }
            }
        }

        fun createP8ServerPropertiesIfMissingOrVerifyRequiredSafety(destination: java.io.File, expected: String) {
            destination.parentFile.mkdirs()
            if (destination.isFile) {
                val expectedP8Properties = loadProperties(expected)
                check(expectedP8Properties.isNotEmpty()) {
                    "P8 server.properties template did not define any safety settings: $p8CurrentManifestShowcaseServerPropertiesTemplate"
                }
                val actualP8Properties = loadProperties(destination.readText(StandardCharsets.UTF_8))
                val mismatches = expectedP8Properties.stringPropertyNames()
                    .sorted()
                    .mapNotNull { key ->
                        val expectedValue = expectedP8Properties.getProperty(key)
                        val actualValue = actualP8Properties.getProperty(key)
                        if (actualValue == expectedValue) {
                            null
                        } else {
                            "$key expected '$expectedValue' but found '${actualValue ?: "<missing>"}'"
                        }
                    }
                check(mismatches.isEmpty()) {
                    "Refusing to use P8 isolated server.properties because required safety setting(s) differ: " +
                            "${mismatches.joinToString("; ")}. Resolve only that exact P8 run directory before retrying; " +
                            "the file was not overwritten."
                }
                return
            }
            check(!destination.exists()) {
                "P8 isolated run target is not a regular file: $destination"
            }
            destination.writeText(expected, StandardCharsets.UTF_8)
        }

        createOnlyIfMissingOrExact(p8CurrentManifestShowcaseServerEula.asFile, "eula=true\n")
        createP8ServerPropertiesIfMissingOrVerifyRequiredSafety(
            p8CurrentManifestShowcaseServerProperties.asFile,
            expectedServerProperties,
        )
    }
}

tasks.named("runP8CurrentManifestShowcaseServer") {
    dependsOn(prepareP8CurrentManifestShowcaseServerRun)
}

data class PhaseSmokeServerHarness(
    val phase: String,
    val runDirectory: String,
    val propertiesTemplate: String,
)

fun loadPhaseSmokeServerProperties(contents: String): Properties {
    return Properties().also { properties ->
        contents.reader().use { reader -> properties.load(reader) }
    }
}

fun createPhaseSmokeFileOnlyIfMissingOrExact(
    destination: java.io.File,
    expected: String,
    phase: String,
) {
    destination.parentFile.mkdirs()
    if (destination.isFile) {
        check(destination.readText(StandardCharsets.UTF_8) == expected) {
            "Refusing to overwrite $phase isolated run file with different contents: $destination. " +
                    "Resolve only that exact $phase run directory before retrying."
        }
        return
    }
    check(!destination.exists()) {
        "$phase isolated run target is not a regular file: $destination"
    }
    destination.writeText(expected, StandardCharsets.UTF_8)
}

fun createPhaseSmokeServerPropertiesIfMissingOrVerifyRequiredSafety(
    destination: java.io.File,
    expected: String,
    phase: String,
    template: java.io.File,
) {
    destination.parentFile.mkdirs()
    if (destination.isFile) {
        val expectedProperties = loadPhaseSmokeServerProperties(expected)
        check(expectedProperties.isNotEmpty()) {
            "$phase server.properties template did not define any safety settings: $template"
        }
        val actualProperties = loadPhaseSmokeServerProperties(destination.readText(StandardCharsets.UTF_8))
        val mismatches = expectedProperties.stringPropertyNames()
            .sorted()
            .mapNotNull { key ->
                val expectedValue = expectedProperties.getProperty(key)
                val actualValue = actualProperties.getProperty(key)
                if (actualValue == expectedValue) {
                    null
                } else {
                    "$key expected '$expectedValue' but found '${actualValue ?: "<missing>"}'"
                }
            }
        check(mismatches.isEmpty()) {
            "Refusing to use $phase isolated server.properties because required safety setting(s) differ: " +
                    "${mismatches.joinToString("; ")}. Resolve only that exact $phase run directory before retrying; " +
                    "the file was not overwritten."
        }
        return
    }
    check(!destination.exists()) {
        "$phase isolated run target is not a regular file: $destination"
    }
    destination.writeText(expected, StandardCharsets.UTF_8)
}

val phaseSmokeServerHarnesses = listOf(
    PhaseSmokeServerHarness("P3", "run/p3-smoke-server", "test-assets/p3-smoke-run/server.properties.template"),
    PhaseSmokeServerHarness("P4", "run/p4-smoke-server", "test-assets/p4-smoke-run/server.properties.template"),
    PhaseSmokeServerHarness("P5", "run/p5-smoke-server", "test-assets/p5-smoke-run/server.properties.template"),
    PhaseSmokeServerHarness("P7", "run/p7-smoke-server", "test-assets/p7-smoke-run/server.properties.template"),
)

phaseSmokeServerHarnesses.forEach { harness ->
    val runDirectory = layout.projectDirectory.dir(harness.runDirectory)
    val propertiesTemplate = rootProject.layout.projectDirectory.file(harness.propertiesTemplate)
    val eula = runDirectory.file("eula.txt")
    val serverProperties = runDirectory.file("server.properties")
    val prepareTask = tasks.register("prepare${harness.phase}SmokeServerRun") {
        group = "fabric"
        description = "Creates the ${harness.phase} loopback-only smoke server files without touching normal Showcase runs."
        inputs.file(propertiesTemplate)
        outputs.file(eula)
        outputs.file(serverProperties)

        doLast {
            val expectedServerProperties = propertiesTemplate.asFile.readText(StandardCharsets.UTF_8)
            createPhaseSmokeFileOnlyIfMissingOrExact(eula.asFile, "eula=true\n", harness.phase)
            createPhaseSmokeServerPropertiesIfMissingOrVerifyRequiredSafety(
                serverProperties.asFile,
                expectedServerProperties,
                harness.phase,
                propertiesTemplate.asFile,
            )
        }
    }
    tasks.named("run${harness.phase}SmokeServer") {
        dependsOn(prepareTask)
    }
}

val p7ReferenceAssetsDirectory = layout.buildDirectory.dir("generated/p7-reference-assets")
val p7BenchmarkRunDirectory = layout.projectDirectory.dir("run/p7-benchmark")
val p7BenchmarkResourcePackDirectory = p7BenchmarkRunDirectory.dir("resourcepacks/blendlib-p7-reference")

val generateP7ReferenceAssets = tasks.register<JavaExec>("generateP7ReferenceAssets") {
    group = "verification"
    description = "Generates the dormant P7 reference-scene resource-pack bundle outside normal Showcase resources."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.liy.blendlib.showcase.perf.P7ReferenceAssetGenerator")
    args(p7ReferenceAssetsDirectory.get().asFile.absolutePath)
    outputs.dir(p7ReferenceAssetsDirectory)
}

val prepareP7BenchmarkClientRun = tasks.register<Sync>("prepareP7BenchmarkClientRun") {
    group = "verification"
    description = "Copies only the deterministic P7 pack into the isolated P7 benchmark run directory."
    dependsOn(generateP7ReferenceAssets)
    from(p7ReferenceAssetsDirectory)
    into(p7BenchmarkResourcePackDirectory)
    includeEmptyDirs = false
}

val verifyP7BenchmarkClientRun = tasks.register("verifyP7BenchmarkClientRun") {
    group = "verification"
    description = "Verifies the P7 pack is prepared only below run/p7-benchmark, never normal Showcase run directories."
    dependsOn(prepareP7BenchmarkClientRun)

    doLast {
        val resourcePackRoot = p7BenchmarkResourcePackDirectory.asFile
        check(resourcePackRoot.resolve("pack.mcmeta").isFile) {
            "P7 benchmark pack is missing pack.mcmeta: $resourcePackRoot"
        }
        check(resourcePackRoot.resolve("assets/blendlib_showcase/p7/reference-scene.json").isFile) {
            "P7 benchmark pack is missing its reference scene manifest: $resourcePackRoot"
        }
        check(resourcePackRoot.resolve("assets/blendlib_showcase/models3d/p7/rigid_10k.glb").isFile
                && resourcePackRoot.resolve("assets/blendlib_showcase/models3d/p7/skinned_20k_64j.glb").isFile) {
            "P7 benchmark pack is missing a generated reference GLB: $resourcePackRoot"
        }
        val normalClientPack = layout.projectDirectory
            .dir("run/client/resourcepacks/blendlib-p7-reference").asFile
        val normalServerPack = layout.projectDirectory
            .dir("run/server/resourcepacks/blendlib-p7-reference").asFile
        check(!normalClientPack.exists() && !normalServerPack.exists()) {
            "P7 benchmark pack must not be prepared in normal Showcase run directories"
        }
    }
}

tasks.named("runP7BenchmarkClient") {
    dependsOn(prepareP7BenchmarkClientRun)
}

data class P7IrisSodiumSmokeMod(
    val displayName: String,
    val versionId: String,
    val fileName: String,
    val sha1: String,
    val downloadUrl: String,
)

// These are fixed by the P7 smoke evidence rather than a floating repository coordinate. The
// dedicated task below is opt-in: no default development, packaging, publication, or alpha task
// depends on it or receives these JARs on its classpath.
val p7IrisSodiumSmokeMods = listOf(
    P7IrisSodiumSmokeMod(
        displayName = "Iris 1.11.2 for Fabric 26.1.2",
        versionId = "e4ioH5mG",
        fileName = "iris-fabric-1.11.2+mc26.1.2.jar",
        sha1 = "5f23dc2bae9fa28a18ef1ec6a60c0d6f8fcc5b13",
        downloadUrl = "https://cdn.modrinth.com/data/YL57xq9U/versions/e4ioH5mG/iris-fabric-1.11.2%2Bmc26.1.2.jar",
    ),
    P7IrisSodiumSmokeMod(
        displayName = "Sodium 0.9.1 for Fabric 26.1.2",
        versionId = "vf7UgZpC",
        fileName = "sodium-fabric-0.9.1+mc26.1.2.jar",
        sha1 = "cdb5ab59dc05840c5fc762c3821570c6fa02a8dc",
        downloadUrl = "https://cdn.modrinth.com/data/AANobbMI/versions/vf7UgZpC/sodium-fabric-0.9.1%2Bmc26.1.2.jar",
    ),
)
val p7IrisSodiumSmokeRunDirectory = layout.projectDirectory.dir("run/p7-iris-sodium")
val p7IrisSodiumSmokeModsDirectory = p7IrisSodiumSmokeRunDirectory.dir("mods")
val p7IrisSodiumSmokeModNames = p7IrisSodiumSmokeMods.map(P7IrisSodiumSmokeMod::fileName).toSet()

fun sha1(file: java.io.File): String {
    val digest = MessageDigest.getInstance("SHA-1")
    file.inputStream().use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) {
                break
            }
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

fun verifyP7IrisSodiumSmokeModsDirectory() {
    val directory = p7IrisSodiumSmokeModsDirectory.asFile
    check(directory.isDirectory) { "P7 Iris/Sodium smoke mods directory is missing: $directory" }
    val contents = directory.listFiles()?.toList().orEmpty()
    val unexpected = contents.filter { !it.isFile || it.name !in p7IrisSodiumSmokeModNames }
    check(unexpected.isEmpty()) {
        "P7 Iris/Sodium smoke mods directory may contain only the two pinned JARs; unexpected=$unexpected"
    }
    check(contents.map { file -> file.name }.toSet() == p7IrisSodiumSmokeModNames) {
        "P7 Iris/Sodium smoke mods directory must contain exactly $p7IrisSodiumSmokeModNames, found=$contents"
    }
    p7IrisSodiumSmokeMods.forEach { mod ->
        val file = directory.resolve(mod.fileName)
        check(file.isFile) { "Missing pinned ${mod.displayName} JAR: $file" }
        val actualSha1 = sha1(file)
        check(actualSha1 == mod.sha1) {
            "Pinned ${mod.displayName} SHA-1 mismatch: expected=${mod.sha1} actual=$actualSha1 file=$file"
        }
    }
}

val prepareP7IrisSodiumSmokeClientRun = tasks.register("prepareP7IrisSodiumSmokeClientRun") {
    group = "verification"
    description = "Downloads and SHA-1 verifies only the two pinned Iris/Sodium JARs into the isolated P7 smoke run."
    inputs.property(
        "p7IrisSodiumSmokeMods",
        p7IrisSodiumSmokeMods.joinToString(separator = "|") { mod ->
            "${mod.versionId}:${mod.fileName}:${mod.sha1}:${mod.downloadUrl}"
        },
    )
    outputs.dir(p7IrisSodiumSmokeModsDirectory)

    doLast {
        val directory = p7IrisSodiumSmokeModsDirectory.asFile
        if (!directory.exists()) {
            check(directory.mkdirs()) { "Unable to create isolated P7 Iris/Sodium mods directory: $directory" }
        }
        check(directory.isDirectory) { "P7 Iris/Sodium mods target is not a directory: $directory" }
        val existing = directory.listFiles()?.toList().orEmpty()
        val unexpected = existing.filter { !it.isFile || it.name !in p7IrisSodiumSmokeModNames }
        check(unexpected.isEmpty()) {
            "Refusing to alter an isolated smoke mods directory with unexpected content: $unexpected"
        }

        val client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
        p7IrisSodiumSmokeMods.forEach { mod ->
            val destination = directory.resolve(mod.fileName)
            if (destination.exists()) {
                check(destination.isFile) { "Pinned smoke destination is not a file: $destination" }
                val actualSha1 = sha1(destination)
                check(actualSha1 == mod.sha1) {
                    "Refusing to replace unexpected existing ${mod.displayName} content at $destination; expected=${mod.sha1} actual=$actualSha1"
                }
                return@forEach
            }

            val staging = directory.resolve("${mod.fileName}.download")
            check(!staging.exists()) {
                "Refusing to overwrite an incomplete P7 Iris/Sodium download: $staging"
            }
            var moved = false
            try {
                val response = client.send(
                    HttpRequest.newBuilder(URI.create(mod.downloadUrl))
                        .header("User-Agent", "BlendLib-local-P7-smoke/1.0")
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofFile(staging.toPath()),
                )
                check(response.statusCode() == 200) {
                    "Unable to resolve pinned ${mod.displayName}: HTTP ${response.statusCode()} from ${mod.downloadUrl}"
                }
                val actualSha1 = sha1(staging)
                check(actualSha1 == mod.sha1) {
                    "Resolved ${mod.displayName} SHA-1 mismatch: expected=${mod.sha1} actual=$actualSha1"
                }
                Files.move(staging.toPath(), destination.toPath())
                moved = true
            } finally {
                if (!moved) {
                    Files.deleteIfExists(staging.toPath())
                }
            }
        }
        verifyP7IrisSodiumSmokeModsDirectory()
    }
}

val verifyP7IrisSodiumSmokeClientRun = tasks.register("verifyP7IrisSodiumSmokeClientRun") {
    group = "verification"
    description = "Verifies pinned Iris/Sodium smoke JARs remain isolated from normal Showcase runs and artifacts."
    dependsOn(prepareP7IrisSodiumSmokeClientRun, tasks.named("jar"))

    doLast {
        verifyP7IrisSodiumSmokeModsDirectory()

        listOf(
            layout.projectDirectory.dir("run/client/mods").asFile,
            layout.projectDirectory.dir("run/server/mods").asFile,
            layout.projectDirectory.dir("run/p7-benchmark/mods").asFile,
        ).forEach { normalModsDirectory ->
            val collisions = normalModsDirectory.listFiles()?.filter { it.name in p7IrisSodiumSmokeModNames }.orEmpty()
            check(collisions.isEmpty()) {
                "Pinned Iris/Sodium smoke JARs must not be copied into a normal Showcase run: $collisions"
            }
        }

        // The optional JARs are downloaded directly by the opt-in preparation task rather than
        // added to any normal development/runtime/publication configuration. Resolve the normal
        // classpaths anyway so this remains an executable proof, not merely a build-script claim.
        listOf("compileClasspath", "runtimeClasspath", "clientCompileClasspath", "clientRuntimeClasspath")
            .forEach { configurationName ->
                val optionalModules = configurations.named(configurationName).get().incoming.resolutionResult
                    .allComponents
                    .mapNotNull { component -> component.id as? ModuleComponentIdentifier }
                    .filter { component ->
                        component.group == "maven.modrinth" && component.module in setOf("iris", "sodium")
                    }
                    .map { component -> "${component.group}:${component.module}:${component.version}" }
                check(optionalModules.isEmpty()) {
                    "Default Showcase $configurationName must not resolve optional Iris/Sodium smoke modules: $optionalModules"
                }
            }

        val showcaseJar = tasks.named<org.gradle.api.tasks.bundling.Jar>("jar").get().archiveFile.get().asFile
        ZipFile(showcaseJar).use { archive ->
            val bundledOptional = archive.entries().asSequence()
                .map { entry -> entry.name.substringAfterLast('/') }
                .filter { entryName -> entryName in p7IrisSodiumSmokeModNames }
                .toList()
            check(bundledOptional.isEmpty()) {
                "Default Showcase JAR must not bundle optional Iris/Sodium smoke JARs: $bundledOptional"
            }
        }
    }
}

tasks.named("runP7IrisSodiumSmokeClient") {
    dependsOn(verifyP7IrisSodiumSmokeClientRun)
}

tasks.register("remapJar") {
    group = "build"
    description = "Compatibility alias: 26.1.2 uses Loom's non-obfuscated Mojang namespace."
    dependsOn(tasks.named("jar"))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = StandardCharsets.UTF_8.name()
}
