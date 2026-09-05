import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    `java-library`
}

group = providers.gradleProperty("maven_group").get()
version = providers.gradleProperty("x8_datagen_version").get()

base {
    archivesName.set("blendlib-datagen")
}

extensions.configure<JavaPluginExtension> {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    api(project(":blendlib-api"))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
    options.compilerArgs.add("-Xlint:all")
}

tasks.withType<Jar>().configureEach {
    manifest {
        attributes(mapOf(
            "BlendLib-Artifact" to "datagen",
            "BlendLib-Artifact-State" to "x8-local-candidate",
            "BlendLib-Datagen-Contract" to "descriptor-v1-sidecars-v1",
            "BlendLib-Platform-Dependency" to "none",
            "BlendLib-License-Ref" to "Apache-2.0"
        ))
    }
}
