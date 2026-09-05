rootProject.name = "blendlib-neoforge-26.2"

// The bridge consumes the root pure-Java artifacts through the same checkout rather than embedding
// API/core sources in a waiting NeoForge JAR.
includeBuild("../..") {
    dependencySubstitution {
        substitute(module("com.liy.blendlib:blendlib-api")).using(project(":blendlib-api"))
        substitute(module("com.liy.blendlib:blendlib-core")).using(project(":blendlib-core"))
    }
}
