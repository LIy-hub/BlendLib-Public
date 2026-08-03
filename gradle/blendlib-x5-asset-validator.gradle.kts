import java.io.File
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.Task
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.Optional
import org.gradle.process.CommandLineArgumentProvider

/*
 * X5-owned, opt-in Gradle script plugin for the pure-Java asset validator.
 * Applying this file is the only shared-build wiring required from integration.
 */

abstract class BlendlibX5ValidatorArguments : CommandLineArgumentProvider {
    @get:Input
    @get:Optional
    abstract val projectRoot: Property<String>

    @get:Input
    @get:Optional
    abstract val modelKey: Property<String>

    @get:Input
    abstract val format: Property<String>

    @get:Input
    abstract val resourceRoot: Property<String>

    @get:Input
    abstract val authoringRoot: Property<String>

    @get:Input
    @get:Optional
    abstract val report: Property<String>

    @get:Input
    @get:Optional
    abstract val sidecar: Property<String>

    override fun asArguments(): Iterable<String> {
        val arguments = mutableListOf(
            "--project-root", required(projectRoot, "blendlibAssetProjectRoot"),
            "--model-key", required(modelKey, "blendlibAssetModelKey"),
            "--format", format.get(),
            "--resource-root", resourceRoot.get(),
            "--authoring-root", authoringRoot.get(),
        )
        report.orNull?.takeIf(String::isNotBlank)?.let {
            arguments.addAll(listOf("--report", it))
        }
        sidecar.orNull?.takeIf(String::isNotBlank)?.let {
            arguments.addAll(listOf("--sidecar", it))
        }
        return arguments
    }

    private fun required(property: Property<String>, name: String): String = property.orNull
        ?.takeIf(String::isNotBlank)
        ?: throw GradleException("Missing required -P$name value.")
}

class BlendlibX5ClasspathCheck : Action<Task> {
    override fun execute(task: Task) {
        if ((task as JavaExec).classpath.files.isEmpty()) {
            throw GradleException(
                "BlendLib validator classpath is empty; apply in the BlendLib root or set -PblendlibValidatorClasspath.",
            )
        }
    }
}

val blendlibX5ValidatorClasspath = configurations.create("blendlibX5ValidatorClasspath") {
    isCanBeConsumed = false
    isCanBeResolved = true
    description = "Classpath used only by the local BlendLib X5 asset validator."
}

val coreProject = rootProject.findProject(":blendlib-core")
if (coreProject != null) {
    dependencies.add(blendlibX5ValidatorClasspath.name, project(":blendlib-core"))
} else {
    val isolatedClasspath = providers.gradleProperty("blendlibValidatorClasspath").orNull
    if (!isolatedClasspath.isNullOrBlank()) {
        dependencies.add(
            blendlibX5ValidatorClasspath.name,
            files(isolatedClasspath.split(File.pathSeparatorChar).filter(String::isNotBlank)),
        )
    }
}

val blendlibX5Arguments = objects.newInstance(BlendlibX5ValidatorArguments::class.java).apply {
    projectRoot.set(providers.gradleProperty("blendlibAssetProjectRoot"))
    modelKey.set(providers.gradleProperty("blendlibAssetModelKey"))
    format.convention(providers.gradleProperty("blendlibAssetFormat").orElse("text"))
    resourceRoot.convention(providers.gradleProperty("blendlibAssetResourceRoot").orElse("src/main/resources/assets"))
    authoringRoot.convention(providers.gradleProperty("blendlibAssetAuthoringRoot").orElse("build/blendlib-authoring"))
    report.set(providers.gradleProperty("blendlibAssetReport"))
    sidecar.set(providers.gradleProperty("blendlibAssetSidecar"))
}

tasks.register<JavaExec>("validateBlendlibAsset") {
    group = "verification"
    description = "Validates one strict-v1 BlendLib bundle and its X5 authoring artifacts."
    mainClass.set("com.liy.blendlib.core.tooling.AssetValidatorCli")
    classpath = blendlibX5ValidatorClasspath
    if (coreProject != null) {
        dependsOn(":blendlib-core:classes")
    }
    argumentProviders.add(blendlibX5Arguments)
    doFirst(BlendlibX5ClasspathCheck())
}
