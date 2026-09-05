base {
    archivesName.set("blendlib-material-provider-example")
}

dependencies {
    api("com.liy.blendlib:blendlib-api:${providers.gradleProperty("blendlib_alpha_version").get()}")
}
