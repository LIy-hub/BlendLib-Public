pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
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
        maven("https://maven.fabricmc.net/")
        mavenCentral()
    }
}

rootProject.name = "blendlib-independent-consumer"
