pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { content { includeGroupByRegex("net\\.fabricmc(\\..*)?") } }
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        id("net.fabricmc.fabric-loom") version "1.15.5"
        id("net.fabricmc.fabric-loom-remap") version "1.15.5"
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
