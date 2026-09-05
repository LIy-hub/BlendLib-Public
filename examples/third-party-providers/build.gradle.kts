import java.nio.charset.StandardCharsets
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion

allprojects {
    group = "com.liy.blendlib.examples.providers"
    version = providers.gradleProperty("blendlib_alpha_version").get()
}

subprojects {
    apply(plugin = "java-library")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
        withSourcesJar()
        withJavadocJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = StandardCharsets.UTF_8.name()
        options.release.set(25)
    }
}
