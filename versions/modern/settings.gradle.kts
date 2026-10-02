pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { content { includeGroupByRegex("net\\.fabricmc(\\..*)?") } }
        gradlePluginPortal()
        mavenCentral()
    }
    val loomVersion = if (providers.gradleProperty("minecraft_version").orNull == "26.3") "1.17.21" else "1.15.5"
    plugins {
        id("net.fabricmc.fabric-loom") version loomVersion
        id("net.fabricmc.fabric-loom-remap") version loomVersion
    }
}

dependencyResolutionManagement {
    repositories {
        maven(rootDir.resolve("build/fabric-maven")) {
            content { includeGroup("net.fabricmc.fabric-api") }
        }
        maven("https://maven.fabricmc.net/") { content { includeGroupByRegex("net\\.fabricmc(\\..*)?") } }
        mavenCentral()
    }
}

rootProject.name = "blendlib-modern"
