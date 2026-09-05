base {
    archivesName.set("blendlib-host-adapter-example")
}

dependencies {
    api("com.liy.blendlib:blendlib-api:${providers.gradleProperty("blendlib_alpha_version").get()}")
}
