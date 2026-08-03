import java.nio.charset.StandardCharsets
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmVersion
import org.gradle.api.tasks.bundling.Jar

plugins {
    id("net.fabricmc.fabric-loom")
}

base {
    archivesName.set("blendlib-fabric")
}

val minecraftVersion = providers.gradleProperty("minecraft_version").get()
val loaderVersion = providers.gradleProperty("loader_version").get()
val fabricVersion = providers.gradleProperty("fabric_version").get()

loom {
    splitEnvironmentSourceSets()

    mods {
        create("blendlib") {
            sourceSet(sourceSets["main"])
            sourceSet(sourceSets["client"])
        }
    }
}

dependencies {
    implementation(project(":blendlib-api"))
    implementation(project(":blendlib-core"))
    implementation(project(":blendlib-fabric-common"))

    minecraft("com.mojang:minecraft:$minecraftVersion")
    // Minecraft 26.1.2 is already distributed in the official Mojang namespace.
    // Loom 1.15.5 runs it in non-obfuscated mode and rejects a second mappings layer.
    implementation("net.fabricmc:fabric-loader:$loaderVersion")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricVersion")

    include(project(":blendlib-api"))
    include(project(":blendlib-core"))
    include(project(":blendlib-fabric-common"))
}

/**
 * Explicit consumer-facing artifact for Loom's split client source set.
 *
 * <p>The standard Java variants expose only {@code build/classes/java/main} to project consumers. Loom's jar task
 * contains both main and client output, so client consumers must select this JAR-only variant rather than the main
 * classes secondary variant.</p>
 */
val clientAdapterApiElements = configurations.create("clientAdapterApiElements") {
    isCanBeConsumed = true
    isCanBeResolved = false

    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_API))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
    }

    outgoing.artifact(tasks.named("jar"))
}

val clientAdapterRuntimeElements = configurations.create("clientAdapterRuntimeElements") {
    isCanBeConsumed = true
    isCanBeResolved = false

    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
    }

    outgoing.artifact(tasks.named("jar"))
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Implementation-Title" to "BlendLib Fabric 26.1.2 Adapter",
            "Implementation-Version" to project.version,
            "BlendLib-License" to "Apache-2.0",
        )
    }
}

tasks.register("remapJar") {
    group = "build"
    description = "Compatibility alias: 26.1.2 uses Loom's non-obfuscated Mojang namespace."
    dependsOn(tasks.named("jar"))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = StandardCharsets.UTF_8.name()
}
