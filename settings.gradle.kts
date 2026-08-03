pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        mavenCentral()
    }
}

rootProject.name = "blendlib"

include(
    "blendlib-api",
    "blendlib-core",
    "blendlib-fabric-common",
    "blendlib-fabric-client",
    "blendlib-showcase",
    "blendlib-api-consumer-fixture",
    "blendlib-fabric-consumer-fixture",
)
