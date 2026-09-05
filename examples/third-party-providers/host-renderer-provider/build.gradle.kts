base {
    archivesName.set("blendlib-host-renderer-provider-example")
}

dependencies {
    api("com.liy.blendlib:blendlib-api:${providers.gradleProperty("blendlib_alpha_version").get()}")
}
