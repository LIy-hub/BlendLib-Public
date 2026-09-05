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

rootProject.name = "blendlib-fabric-26.2"

// Keep API/core as independent pure-Java artifacts. The standalone adapter compiles against the
// same checkout through explicit composite substitution; it never copies their source classes into
// the Fabric 26.2 JAR or aliases the 26.1.2 runtime artifact.
includeBuild("../..") {
    dependencySubstitution {
        substitute(module("com.liy.blendlib:blendlib-api")).using(project(":blendlib-api"))
        substitute(module("com.liy.blendlib:blendlib-core")).using(project(":blendlib-core"))
    }
}
