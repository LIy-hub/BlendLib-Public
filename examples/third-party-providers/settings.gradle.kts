pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        maven {
            name = "blendLibLocalRc"
            url = file(providers.gradleProperty("blendlib_local_maven_repo").get()).toURI()
            content {
                includeGroup("com.liy.blendlib")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "blendlib-third-party-providers"
include(
    "asset-profile-provider",
    "material-provider",
    "render-backend-provider",
    "host-renderer-provider",
    "host-adapter",
)
