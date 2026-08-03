import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier

import java.nio.charset.StandardCharsets
import java.util.Properties
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    id("net.fabricmc.fabric-loom") version "1.15.5"
}

val minecraftVersion = providers.gradleProperty("minecraft_version").get()
val loaderVersion = providers.gradleProperty("loader_version").get()
val fabricVersion = providers.gradleProperty("fabric_version").get()
val blendLibVersion = providers.gradleProperty("blendlib_alpha_version").get()
val blendLibCoordinate = "com.liy.blendlib:blendlib-fabric:$blendLibVersion"
val localMavenRepository = file(providers.gradleProperty("blendlib_local_maven_repo").get()).toPath()

description = "Blank Fabric consumer fixture resolving the BlendLib public alpha through local Maven coordinates."
group = "com.liy.blendlib.fixture"
version = blendLibVersion

val localAlphaRepository = repositories.maven {
    name = "blendLibLocalAlpha"
    url = localMavenRepository.toUri()
    content {
        includeGroup("com.liy.blendlib")
    }
}
repositories.remove(localAlphaRepository)
repositories.addFirst(localAlphaRepository)

extensions.configure<JavaPluginExtension> {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = StandardCharsets.UTF_8.name()
    options.release.set(25)
    options.compilerArgs.add("-Xlint:all")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("blendlib.projectDir", project.projectDir.absolutePath)
}

loom {
    mods {
        create("blendlib_local_maven_consumer") {
            sourceSet(sourceSets["main"])
        }
    }

    runs {
        named("server") {
            setConfigName("BlendLib Local Maven Consumer Server")
            runDir("run/server")
        }
        // This opt-in P8 run never shares the historical/default consumer server directory.
        // It starts the fixture from its Maven coordinate; it is not a packaged external installer.
        create("p8CurrentManifestConsumerServer") {
            server()
            setConfigName("BlendLib Local Maven Consumer P8 Current-Manifest Server (loopback isolated)")
            runDir("run/p8-current-manifest-server")
        }
    }
}

dependencies {
    // This is intentionally a Maven coordinate, never a project dependency.
    implementation(blendLibCoordinate)

    minecraft("com.mojang:minecraft:$minecraftVersion")
    implementation("net.fabricmc:fabric-loader:$loaderVersion")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricVersion")
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val verifyLocalMavenResolution = configurations.create("verifyLocalMavenResolution") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    add(verifyLocalMavenResolution.name, blendLibCoordinate)
}

val verifyLocalMavenConsumerBoundary = tasks.register("verifyLocalMavenConsumerBoundary") {
    group = "verification"
    description = "Proves the blank consumer resolves BlendLib only from the generated local Maven repository."

    doLast {
        val compileProjects = configurations.named("compileClasspath").get().incoming.resolutionResult.allComponents
            .mapNotNull { component ->
                (component.id as? ProjectComponentIdentifier)?.projectPath
            }
            .toSet()
        check(compileProjects == setOf(project.path)) {
            "Local Maven consumer must not resolve BlendLib through a project dependency: $compileProjects"
        }

        val localComponents = verifyLocalMavenResolution.incoming.resolutionResult.allComponents
            .mapNotNull { component -> component.id as? ModuleComponentIdentifier }
        check(localComponents.any {
            it.group == "com.liy.blendlib"
                    && it.module == "blendlib-fabric"
                && it.version == blendLibVersion
        }) {
            "Local Maven consumer did not resolve $blendLibCoordinate: $localComponents"
        }

        val resolvedArtifact = verifyLocalMavenResolution.incoming.artifactView { }.artifacts.artifacts
            .singleOrNull { artifact ->
                val identifier = artifact.id.componentIdentifier as? ModuleComponentIdentifier
                identifier?.group == "com.liy.blendlib" && identifier.module == "blendlib-fabric"
            }
            ?: error("Local Maven consumer did not expose the BlendLib runtime artifact")
        val expectedRepositoryRoot: java.nio.file.Path = localMavenRepository.toAbsolutePath().normalize()
        val resolvedPath: java.nio.file.Path = resolvedArtifact.file.toPath().toAbsolutePath().normalize()
        check(resolvedPath.startsWith(expectedRepositoryRoot)) {
            "BlendLib runtime must resolve from $expectedRepositoryRoot, not $resolvedPath"
        }

        val buildScript = layout.projectDirectory.file("build.gradle.kts").asFile.readText()
        val projectDependencyPattern = Regex(
            "(?m)^(?:\\s*)(?:implementation|api|compileOnly|runtimeOnly|testImplementation)\\s*\\(\\s*project\\(")
        check(!projectDependencyPattern.containsMatchIn(buildScript)) {
            "Local Maven consumer build script must not declare project dependencies"
        }
        check("implementation(blendLibCoordinate)" in buildScript) {
            "Local Maven consumer must use the generated Maven coordinate"
        }
    }
}

tasks.named("check") {
    dependsOn(verifyLocalMavenConsumerBoundary)
}

val prepareLocalMavenConsumerServerRun = tasks.register("prepareLocalMavenConsumerServerRun") {
    group = "fabric"
    description = "Creates the isolated local-Maven consumer server EULA acceptance file."
    val eulaFile = layout.projectDirectory.file("run/server/eula.txt")
    outputs.file(eulaFile)

    doLast {
        val destination = eulaFile.asFile
        destination.parentFile.mkdirs()
        destination.writeText("eula=true\n", StandardCharsets.UTF_8)
    }
}

tasks.named("runServer") {
    dependsOn(prepareLocalMavenConsumerServerRun)
}

val p8CurrentManifestConsumerServerRunDirectory = layout.projectDirectory.dir("run/p8-current-manifest-server")
val p8CurrentManifestConsumerSmokeAssetsDirectory = project.projectDir.parentFile
    .toPath()
    .resolve("test-assets/p8-smoke-run")
    .toFile()
val p8CurrentManifestConsumerServerPropertiesTemplate =
    p8CurrentManifestConsumerSmokeAssetsDirectory.resolve("local-maven-consumer-server.properties.template")
val p8CurrentManifestConsumerServerEula = p8CurrentManifestConsumerServerRunDirectory.file("eula.txt")
val p8CurrentManifestConsumerServerProperties = p8CurrentManifestConsumerServerRunDirectory.file("server.properties")

val prepareP8CurrentManifestConsumerServerRun = tasks.register("prepareP8CurrentManifestConsumerServerRun") {
    group = "fabric"
    description = "Creates the P8 current-manifest loopback-only Local Maven consumer server files."
    inputs.file(p8CurrentManifestConsumerServerPropertiesTemplate)
    outputs.file(p8CurrentManifestConsumerServerEula)
    outputs.file(p8CurrentManifestConsumerServerProperties)

    doLast {
        val expectedServerProperties = p8CurrentManifestConsumerServerPropertiesTemplate.readText(StandardCharsets.UTF_8)

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
                    "P8 server.properties template did not define any safety settings: $p8CurrentManifestConsumerServerPropertiesTemplate"
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

        createOnlyIfMissingOrExact(p8CurrentManifestConsumerServerEula.asFile, "eula=true\n")
        createP8ServerPropertiesIfMissingOrVerifyRequiredSafety(
            p8CurrentManifestConsumerServerProperties.asFile,
            expectedServerProperties,
        )
    }
}

tasks.named("runP8CurrentManifestConsumerServer") {
    // Keep a direct smoke invocation from bypassing the coordinate/path boundary proof.
    dependsOn(prepareP8CurrentManifestConsumerServerRun, verifyLocalMavenConsumerBoundary)
}
