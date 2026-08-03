plugins {
    id("net.fabricmc.fabric-loom")
}

val minecraftVersion = providers.gradleProperty("minecraft_version").get()
val loaderVersion = providers.gradleProperty("loader_version").get()
val fabricVersion = providers.gradleProperty("fabric_version").get()

description = "Compile-only Fabric consumer fixture restricted to BlendLib public API and common facade."

dependencies {
    implementation(project(":blendlib-api"))
    implementation(project(":blendlib-fabric-common"))

    minecraft("com.mojang:minecraft:$minecraftVersion")
    implementation("net.fabricmc:fabric-loader:$loaderVersion")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricVersion")
}

val verifyFabricConsumerFixtureDependencyBoundary = tasks.register("verifyFabricConsumerFixtureDependencyBoundary") {
    group = "verification"
    description = "Verifies that the Fabric consumer fixture compiles only against public BlendLib modules."

    doLast {
        val projectDependencies = configurations.named("compileClasspath").get().incoming.resolutionResult.allComponents
            .mapNotNull { component ->
                (component.id as? org.gradle.api.artifacts.component.ProjectComponentIdentifier)?.projectPath
            }
            .toSet()
        val allowed = setOf(project.path, ":blendlib-api", ":blendlib-fabric-common")
        val unexpected = projectDependencies - allowed
        check(unexpected.isEmpty()) {
            "Fabric consumer compileClasspath exposes non-public BlendLib projects: $unexpected"
        }
        val missing = allowed - projectDependencies
        check(missing.isEmpty()) {
            "Fabric consumer compileClasspath is missing its public BlendLib dependencies: $missing"
        }
    }
}

tasks.named("check") {
    dependsOn(verifyFabricConsumerFixtureDependencyBoundary)
}
