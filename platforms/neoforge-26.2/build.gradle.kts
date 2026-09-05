import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    `java-library`
}

group = providers.gradleProperty("maven_group").get()
version = providers.gradleProperty("x8_neoforge262_version").get()

base {
    archivesName.set("blendlib-neoforge-26.2-waiting")
}

extensions.configure<JavaPluginExtension> {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    implementation("com.liy.blendlib:blendlib-api:${providers.gradleProperty("blendlib_runtime_version").get()}")
    implementation("com.liy.blendlib:blendlib-core:${providers.gradleProperty("blendlib_runtime_version").get()}")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
    options.compilerArgs.add("-Xlint:all")
}

tasks.withType<Jar>().configureEach {
    manifest {
        attributes(mapOf(
            "BlendLib-Artifact" to "neoforge-26.2-waiting-bridge",
            "BlendLib-Minecraft-Target" to "26.2",
            "BlendLib-Adapter-State" to "waiting-official-neoforge-binding",
            "BlendLib-License-Ref" to "Apache-2.0"
        ))
    }
}

val waitingMetadataTemplate = layout.projectDirectory.file(
    "src/main/resources/META-INF/neoforge.mods.toml.template")
val forbiddenLoadableMetadata = layout.projectDirectory.file(
    "src/main/resources/META-INF/neoforge.mods.toml")

val verifyWaitingMetadata = tasks.register("verifyWaitingMetadata") {
    group = "verification"
    description = "Rejects loadable NeoForge metadata while the X8 bridge remains WAITING."
    inputs.file(waitingMetadataTemplate)
    doLast {
        check(waitingMetadataTemplate.asFile.isFile) {
            "Missing NeoForge WAITING metadata template: ${waitingMetadataTemplate.asFile}"
        }
        check(!forbiddenLoadableMetadata.asFile.exists()) {
            "The X8 NeoForge bridge must not package loadable neoforge.mods.toml before official 26.2 binding verification"
        }
    }
}

tasks.named("jar") {
    dependsOn(verifyWaitingMetadata)
}
