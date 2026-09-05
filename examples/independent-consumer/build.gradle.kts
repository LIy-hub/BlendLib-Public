import java.nio.charset.StandardCharsets
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    id("net.fabricmc.fabric-loom") version "1.15.5"
}

val minecraftVersion = providers.gradleProperty("minecraft_version").get()
val loaderVersion = providers.gradleProperty("loader_version").get()
val fabricVersion = providers.gradleProperty("fabric_version").get()
val blendLibVersion = providers.gradleProperty("blendlib_alpha_version").get()
val blendLibCoordinate = "com.liy.blendlib:blendlib-fabric:$blendLibVersion"

group = "com.liy.blendlib.examples"
version = blendLibVersion
description = "Minimal independent Fabric consumer resolved solely through a local BlendLib Maven coordinate."

base {
    archivesName.set("blendlib-independent-consumer")
}

repositories {
    maven {
        name = "blendLibLocalRc"
        url = file(providers.gradleProperty("blendlib_local_maven_repo").get()).toURI()
        content {
            includeGroup("com.liy.blendlib")
        }
    }
    maven("https://maven.fabricmc.net/")
    mavenCentral()
}

extensions.configure<JavaPluginExtension> {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = StandardCharsets.UTF_8.name()
    options.release.set(25)
}

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("blendlibAlphaVersion", blendLibVersion)
    filesMatching("fabric.mod.json") {
        expand(
            "version" to project.version,
            "blendlib_alpha_version" to blendLibVersion,
        )
    }
}

tasks.withType<Jar>().configureEach {
    manifest {
        attributes(mapOf(
            "BlendLib-Artifact" to "independent-consumer-example",
            "BlendLib-Minecraft-Target" to minecraftVersion,
            "BlendLib-License-Ref" to "Apache-2.0",
            "BlendLib-Distribution" to "local-example-only"
        ))
    }
}

loom {
    splitEnvironmentSourceSets()
    mods {
        create("blendlib_independent_consumer") {
            sourceSet(sourceSets["main"])
            sourceSet(sourceSets["client"])
        }
    }
}

dependencies {
    implementation(blendLibCoordinate)
    minecraft("com.mojang:minecraft:$minecraftVersion")
    implementation("net.fabricmc:fabric-loader:$loaderVersion")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricVersion")
}
