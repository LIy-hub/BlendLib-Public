import java.nio.charset.StandardCharsets

plugins {
    id("net.fabricmc.fabric-loom")
}

val minecraftVersion = providers.gradleProperty("minecraft_version").get()
val loaderVersion = providers.gradleProperty("loader_version").get()
val fabricVersion = providers.gradleProperty("fabric_version").get()

loom {
    mods {
        create("blendlib") {
            sourceSet(sourceSets["main"])
        }
    }
}

description = "Server-safe Fabric common entrypoint and animation synchronization adapter."

dependencies {
    api(project(":blendlib-api"))
    implementation(project(":blendlib-core"))

    minecraft("com.mojang:minecraft:$minecraftVersion")
    implementation("net.fabricmc:fabric-loader:$loaderVersion")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricVersion")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = StandardCharsets.UTF_8.name()
}
