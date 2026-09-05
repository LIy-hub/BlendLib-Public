base {
    archivesName.set("blendlib-render-backend-provider-example")
}

dependencies {
    api("com.liy.blendlib:blendlib-api:${providers.gradleProperty("blendlib_alpha_version").get()}")
}
