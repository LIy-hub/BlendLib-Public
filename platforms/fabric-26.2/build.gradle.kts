import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.bundling.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    java
    id("net.fabricmc.fabric-loom") version "1.16.2"
}

group = providers.gradleProperty("maven_group").get()
version = providers.gradleProperty("x8_fabric262_version").get()

base {
    archivesName.set("blendlib-fabric-26.2")
}

extensions.configure<JavaPluginExtension> {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    withSourcesJar()
    withJavadocJar()
}

loom {
    splitEnvironmentSourceSets()

    mods {
        create("blendlib_fabric_262") {
            sourceSet(sourceSets["main"])
            sourceSet(sourceSets["client"])
        }
    }
}

dependencies {
    implementation("com.liy.blendlib:blendlib-api:${providers.gradleProperty("blendlib_runtime_version").get()}")
    implementation("com.liy.blendlib:blendlib-core:${providers.gradleProperty("blendlib_runtime_version").get()}")
    // Loom's include configuration nests the two pure runtime artifacts into this independent
    // Fabric 26.2 remapped mod JAR. The standalone composite substitutes the same coordinates
    // from the root checkout; no API/core source is copied and no 26.1.2 artifact is embedded.
    include("com.liy.blendlib:blendlib-api:${providers.gradleProperty("blendlib_runtime_version").get()}")
    include("com.liy.blendlib:blendlib-core:${providers.gradleProperty("blendlib_runtime_version").get()}")
    minecraft("com.mojang:minecraft:${providers.gradleProperty("minecraft_version").get()}")
    implementation("net.fabricmc:fabric-loader:${providers.gradleProperty("loader_version").get()}")
    implementation("net.fabricmc.fabric-api:fabric-api:${providers.gradleProperty("fabric_version").get()}")
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

tasks.withType<Jar>().configureEach {
    manifest {
        attributes(mapOf(
            "BlendLib-Artifact" to "fabric-26.2",
            "BlendLib-Minecraft-Target" to providers.gradleProperty("minecraft_version").get(),
            "BlendLib-Adapter-State" to "x8-production-candidate",
            "BlendLib-License-Ref" to "Apache-2.0"
        ))
    }
}
