import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import java.util.zip.ZipFile

plugins {
    java
    id("net.fabricmc.fabric-loom") version "1.15.5"
}

group = providers.gradleProperty("maven_group").get()
version = providers.gradleProperty("mod_version").get()

base {
    archivesName.set("blendlib-fabric-26.2-spike")
}

val repositoryRoot = layout.projectDirectory.dir("../..").asFile

extensions.configure<JavaPluginExtension> {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    withSourcesJar()
}

loom {
    splitEnvironmentSourceSets()

    mods {
        create("blendlib_v262_spike") {
            sourceSet(sourceSets["main"])
            sourceSet(sourceSets["client"])
        }
    }
}

sourceSets {
    named("main") {
        java.srcDir(repositoryRoot.resolve("blendlib-api/src/main/java"))
        java.srcDir(repositoryRoot.resolve("blendlib-core/src/main/java"))
    }
    named("test") {
        java.srcDir(repositoryRoot.resolve("blendlib-core/src/test/java"))
        resources.srcDir(repositoryRoot.resolve("blendlib-core/src/test/resources"))
    }
}

dependencies {
    minecraft("com.mojang:minecraft:${providers.gradleProperty("minecraft_version").get()}")
    implementation("net.fabricmc:fabric-loader:${providers.gradleProperty("loader_version").get()}")
    implementation("net.fabricmc.fabric-api:fabric-api:${providers.gradleProperty("fabric_version").get()}")

    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
    options.compilerArgs.add("-Xlint:all")
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Reuse the existing core tests without copying their source or fixtures. Their test helpers
    // derive the repository root from this exact original core-module location.
    systemProperty("blendlib.projectDir", repositoryRoot.resolve("blendlib-core").absolutePath)
}

tasks.register("spikeJar") {
    group = "build"
    description = "Builds the separate Fabric 26.2 BlendLib adapter-spike JAR."
    dependsOn(tasks.named("jar"))
}

val verifySpikeIsolation = tasks.register("verifySpikeIsolation") {
    group = "verification"
    description = "Rejects accidental 26.1.2 runtime-adapter linkage from the standalone 26.2 spike JAR."
    dependsOn(tasks.named("jar"))

    doLast {
        val projectDependencies = configurations.flatMap { configuration ->
            configuration.dependencies.withType(ProjectDependency::class.java)
        }
        check(project.parent == null && rootProject.subprojects.isEmpty() && projectDependencies.isEmpty()) {
            "The standalone spike must not declare a Gradle parent or project dependency"
        }
        val jarFile = tasks.named<Jar>("jar").get().archiveFile.get().asFile
        ZipFile(jarFile).use { archive ->
            val entries = archive.entries().asSequence().map { it.name }.toList()
            check("fabric.mod.json" in entries) { "The spike JAR is missing Fabric metadata" }
            check(entries.any { it.startsWith("com/liy/blendlib/fabric/v262/") }) {
                "The spike JAR is missing its version-specific adapter package"
            }
            check(entries.none { it.startsWith("com/liy/blendlib/fabric/client/") }) {
                "The spike JAR accidentally contains the 26.1.2 client adapter package"
            }
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.named("spikeJar"))
    dependsOn(verifySpikeIsolation)
}
