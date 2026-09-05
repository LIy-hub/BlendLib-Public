base {
    archivesName.set("blendlib-asset-profile-provider-example")
}

dependencies {
    api("com.liy.blendlib:blendlib-api:${providers.gradleProperty("blendlib_alpha_version").get()}")
}
