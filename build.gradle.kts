import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    base
    id("net.fabricmc.fabric-loom") version "1.15.5" apply false
    id("maven-publish")
}

apply(from = "gradle/blendlib-x5-asset-validator.gradle.kts")

group = providers.gradleProperty("maven_group").get()
version = providers.gradleProperty("mod_version").get()

subprojects {
    apply(plugin = "java-library")

    group = rootProject.group
    version = rootProject.version

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
        withSourcesJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(25)
        options.compilerArgs.add("-Xlint:all")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        systemProperty("blendlib.projectDir", project.projectDir.absolutePath)
    }

    dependencies {
        add("testImplementation", platform("org.junit:junit-bom:5.12.2"))
        add("testImplementation", "org.junit.jupiter:junit-jupiter")
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Jar>().configureEach {
        from(rootProject.layout.projectDirectory.file("LICENSE")) {
            into("META-INF")
            rename { "LICENSE" }
        }
        from(rootProject.layout.projectDirectory.file("NOTICE")) {
            into("META-INF")
            rename { "NOTICE" }
        }
    }
}

tasks.register("buildRelease") {
    group = "build"
    description = "Builds the BlendLib modules and consumer fixtures."
    dependsOn(
        ":blendlib-api:check",
        ":blendlib-core:check",
        ":blendlib-fabric-common:check",
        ":blendlib-fabric-client:check",
        ":blendlib-showcase:check",
        ":blendlib-api-consumer-fixture:check",
        ":blendlib-fabric-consumer-fixture:check",
        ":blendlib-fabric-client:remapJar",
        ":blendlib-showcase:remapJar",
    )
}

/**
 * Public alpha assembly. This deliberately uses a single outer Fabric JAR whose nested
 * api/core/common JARs are already produced by the client adapter's Loom include configuration.
 * It never configures a remote repository or mavenLocal publication.
 */
val projectLicenseId = "Apache-2.0"
val projectLicenseName = "Apache License, Version 2.0"
val projectLicenseUrl = "https://www.apache.org/licenses/LICENSE-2.0.txt"
val projectUrl = "https://github.com/LIy-hub/BlendLib-Public"
val projectScmUrl = "https://github.com/LIy-hub/BlendLib-Public.git"
val releaseArtifactId = "blendlib-fabric"
val releaseVersion = version.toString()
val releaseDirectory = layout.buildDirectory.dir("release")
val localMavenDirectory = layout.buildDirectory.dir("local-maven")
val blenderExecutable = providers.gradleProperty("blender_executable")
val blenderAddonDirectory = layout.projectDirectory.dir("blender-addon")
val blenderAddonZip = releaseDirectory.map { it.file("blendlib-exporter-1.0.0.zip") }
val stagedBlenderAddonDirectory = layout.buildDirectory.dir("staged-blender-addon")

/**
 * The repository keeps a P2 schema-verification helper for its source fixtures, but that helper
 * imports the separately installed `jsonschema` package.  It is not part of the installable
 * Blender extension's runtime surface, so assemble the release ZIP from a deterministic staged
 * copy that omits it instead of silently shipping an undeclared dependency.
 */
val stageBlenderAddonForPackaging = tasks.register<Sync>("stageBlenderAddonForPackaging") {
    group = "distribution"
    description = "Stages the GPL-scoped Blender Add-on without source-fixture-only helpers."
    from(blenderAddonDirectory) {
        exclude("scripts/verify_p2_descriptor_schema.py")
        exclude("__pycache__/**", "**/*.pyc")
    }
    into(stagedBlenderAddonDirectory)
}

val fabricClientProject = project(":blendlib-fabric-client")
val showcaseProject = project(":blendlib-showcase")
val apiProject = project(":blendlib-api")
apiProject.pluginManager.apply("maven-publish")

val fabricRuntimeJar = fabricClientProject.tasks.named<Jar>("jar")
val showcaseJar = showcaseProject.tasks.named<Jar>("jar")
val releaseRuntimeJarFile = releaseDirectory.map { it.file("$releaseArtifactId-$releaseVersion.jar") }
val releaseShowcaseJarFile = releaseDirectory.map { it.file("blendlib-showcase-$releaseVersion.jar") }
val releaseSourcesJarFile = releaseDirectory.map { it.file("$releaseArtifactId-$releaseVersion-sources.jar") }
val releaseJavadocJarFile = releaseDirectory.map { it.file("$releaseArtifactId-$releaseVersion-javadoc.jar") }
val releaseJavadocDirectory = releaseDirectory.map { it.dir("javadoc") }

val releaseJavaSourceDirectories = listOf(
    layout.projectDirectory.dir("blendlib-api/src/main/java"),
    layout.projectDirectory.dir("blendlib-core/src/main/java"),
    layout.projectDirectory.dir("blendlib-fabric-common/src/main/java"),
    layout.projectDirectory.dir("blendlib-fabric-client/src/main/java"),
    layout.projectDirectory.dir("blendlib-fabric-client/src/client/java"),
)

val releaseSourcesJar = tasks.register<Jar>("releaseSourcesJar") {
    group = "distribution"
    description = "Builds the aggregate BlendLib 26.1.2 public alpha source JAR."
    archiveBaseName.set(releaseArtifactId)
    archiveVersion.set(releaseVersion)
    archiveClassifier.set("sources")
    destinationDirectory.set(releaseDirectory)
    from(releaseJavaSourceDirectories)
    from(layout.projectDirectory.file("LICENSE")) { into("META-INF") }
    from(layout.projectDirectory.file("NOTICE")) { into("META-INF") }
}

// The client source-set classpath is created by Loom, so evaluate its project before reading it.
evaluationDependsOn(":blendlib-fabric-client")
evaluationDependsOn(":blendlib-api")
val releaseJavadoc = tasks.register<Javadoc>("releaseJavadoc") {
    group = "documentation"
    description = "Generates aggregate Javadoc for the self-contained BlendLib 26.1.2 public alpha."
    source(releaseJavaSourceDirectories)
    classpath = files(
        project(":blendlib-api").configurations.getByName("compileClasspath"),
        project(":blendlib-core").configurations.getByName("compileClasspath"),
        project(":blendlib-fabric-common").configurations.getByName("compileClasspath"),
        fabricClientProject.configurations.getByName("compileClasspath"),
        fabricClientProject.configurations.getByName("clientCompileClasspath"),
    )
    dependsOn(
        ":blendlib-api:classes",
        ":blendlib-core:classes",
        ":blendlib-fabric-common:classes",
        ":blendlib-fabric-client:classes",
        ":blendlib-fabric-client:compileClientJava",
    )
    destinationDir = releaseJavadocDirectory.get().asFile
    options.encoding = StandardCharsets.UTF_8.name()
}

val releaseJavadocJar = tasks.register<Jar>("releaseJavadocJar") {
    group = "distribution"
    description = "Packages aggregate BlendLib 26.1.2 public alpha Javadoc."
    dependsOn(releaseJavadoc)
    archiveBaseName.set(releaseArtifactId)
    archiveVersion.set(releaseVersion)
    archiveClassifier.set("javadoc")
    destinationDirectory.set(releaseDirectory)
    from(releaseJavadocDirectory)
    from(layout.projectDirectory.file("LICENSE")) { into("META-INF") }
    from(layout.projectDirectory.file("NOTICE")) { into("META-INF") }
}

val releaseRuntimeJar = tasks.register("releaseRuntimeJar") {
    group = "distribution"
    description = "Copies the self-contained 26.1.2 Fabric runtime JAR into the public alpha release directory."
    dependsOn(fabricRuntimeJar)
    inputs.file(fabricRuntimeJar.flatMap { it.archiveFile })
    outputs.file(releaseRuntimeJarFile)
    doLast {
        val source = fabricRuntimeJar.get().archiveFile.get().asFile
        val destination = releaseRuntimeJarFile.get().asFile
        destination.parentFile.mkdirs()
        source.copyTo(destination, overwrite = true)
    }
}

val releaseShowcaseJar = tasks.register("releaseShowcaseJar") {
    group = "distribution"
    description = "Copies the independent Showcase consumer JAR into the public alpha release directory."
    dependsOn(showcaseJar)
    inputs.file(showcaseJar.flatMap { it.archiveFile })
    outputs.file(releaseShowcaseJarFile)
    doLast {
        val source = showcaseJar.get().archiveFile.get().asFile
        val destination = releaseShowcaseJarFile.get().asFile
        destination.parentFile.mkdirs()
        source.copyTo(destination, overwrite = true)
    }
}

val validateBlenderAddon = tasks.register<Exec>("validateBlenderAddon") {
    group = "verification"
    description = "Validates the GPL-scoped Blender Add-on before its local ZIP is assembled."
    dependsOn(stageBlenderAddonForPackaging)
    doFirst {
        check(blenderExecutable.isPresent) { "Set blender_executable to package the Blender Add-on" }
        check(file(blenderExecutable.get()).isFile) {
            "Configured Blender executable does not exist: ${blenderExecutable.get()}"
        }
    }
    commandLine(
        blenderExecutable.get(),
        "--background",
        "--command", "extension", "validate",
        "--valid-tags=",
        stagedBlenderAddonDirectory.get().asFile.absolutePath,
    )
}

val packageBlenderAddon = tasks.register<Exec>("packageBlenderAddon") {
    group = "distribution"
    description = "Builds the local GPL-scoped Blender Add-on ZIP without publishing it."
    dependsOn(validateBlenderAddon)
    inputs.dir(stagedBlenderAddonDirectory)
    outputs.file(blenderAddonZip)
    doFirst {
        blenderAddonZip.get().asFile.parentFile.mkdirs()
    }
    commandLine(
        blenderExecutable.get(),
        "--background",
        "--command", "extension", "build",
        "--source-dir", stagedBlenderAddonDirectory.get().asFile.absolutePath,
        "--output-filepath", blenderAddonZip.get().asFile.absolutePath,
        "--valid-tags=",
    )
}

fun verifyBlenderAddonLicenseContract(packageFile: java.io.File) {
    val canonicalLicense = blenderAddonDirectory.file("LICENSE").asFile.readBytes()
    ZipFile(packageFile).use { archive ->
        val licenseEntry = checkNotNull(archive.getEntry("LICENSE")) {
            "Blender Add-on ZIP is missing its GPL LICENSE"
        }
        val packagedLicense = archive.getInputStream(licenseEntry).use { it.readBytes() }
        check(packagedLicense.contentEquals(canonicalLicense)) {
            "Blender Add-on ZIP LICENSE must be byte-identical to blender-addon/LICENSE"
        }

        val manifestEntry = checkNotNull(archive.getEntry("blender_manifest.toml")) {
            "Blender Add-on ZIP is missing blender_manifest.toml"
        }
        val manifest = archive.getInputStream(manifestEntry).use {
            it.readBytes().toString(StandardCharsets.UTF_8)
        }
        val gplDeclaration = Regex(
            "(?m)^\\s*license\\s*=\\s*\\[\\s*\"SPDX:GPL-3\\.0-or-later\"\\s*]\\s*(?:#.*)?$",
        )
        check(gplDeclaration.containsMatchIn(manifest)) {
            "Blender Add-on ZIP manifest must declare only SPDX:GPL-3.0-or-later"
        }

        check(archive.getEntry("NOTICE") == null && archive.getEntry("META-INF/NOTICE") == null) {
            "Blender Add-on ZIP must not contain the root Apache NOTICE"
        }
    }
}

val verifyBlenderAddonPackage = tasks.register("verifyBlenderAddonPackage") {
    group = "verification"
    description = "Verifies the local Add-on ZIP contains only the scoped extension payload and GPL text."
    dependsOn(packageBlenderAddon)

    doLast {
        val packageFile = blenderAddonZip.get().asFile
        check(packageFile.isFile) { "Expected Blender Add-on ZIP: $packageFile" }
        verifyBlenderAddonLicenseContract(packageFile)
        ZipFile(packageFile).use { archive ->
            val entryNames = mutableSetOf<String>()
            val entries = archive.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                check(!entry.name.startsWith("/") && !entry.name.contains("..")) {
                    "Unsafe Blender Add-on ZIP entry: ${entry.name}"
                }
                entryNames += entry.name
            }
            check(setOf("blender_manifest.toml", "LICENSE", "__init__.py", "blendlib_exporter.py")
                .all(entryNames::contains)) {
                "Blender Add-on ZIP is missing required scoped files: $entryNames"
            }
            check("scripts/verify_p2_descriptor_schema.py" !in entryNames) {
                "Blender Add-on ZIP must exclude the source-fixture-only jsonschema helper"
            }
            check(entryNames.none {
                it.startsWith("blendlib-api/") || it.startsWith("blendlib-core/")
                        || it.startsWith("blendlib-fabric-") || it.startsWith("blendlib-showcase/")
                        || it.startsWith("docs/")
            }) {
                "Blender Add-on ZIP must not package non-addon project content: $entryNames"
            }
        }
    }
}

val verifyRuntimeArchive = tasks.register("verifyRuntimeArchive") {
    group = "verification"
    description = "Checks the alpha runtime JAR identity, Apache metadata and notices, nested modules, and forbidden authoring files."
    dependsOn(releaseRuntimeJar)

    doLast {
        val runtimeFile = releaseRuntimeJarFile.get().asFile
        check(runtimeFile.isFile) { "Expected alpha runtime JAR: $runtimeFile" }
        ZipFile(runtimeFile).use { archive ->
            val names = mutableSetOf<String>()
            val entries = archive.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                names += entry.name
                check(!entry.name.lowercase().let { lower ->
                    lower.endsWith(".blend") || lower.endsWith(".fbx") || lower.endsWith(".obj")
                }) {
                    "Runtime JAR contains a forbidden authoring asset: ${entry.name}"
                }
            }
            check("fabric.mod.json" in names) { "Runtime JAR is missing fabric.mod.json" }
            check(setOf("META-INF/LICENSE", "META-INF/NOTICE").all(names::contains)) {
                "Runtime JAR must include the project LICENSE and NOTICE"
            }
            check(setOf(
                "META-INF/jars/blendlib-api-$releaseVersion.jar",
                "META-INF/jars/blendlib-core-$releaseVersion.jar",
                "META-INF/jars/blendlib-fabric-common-$releaseVersion.jar",
            ).all(names::contains)) {
                "Runtime JAR does not contain the expected self-contained nested modules: $names"
            }

            val fabricMetadata = archive.getInputStream(archive.getEntry("fabric.mod.json")).use {
                it.readBytes().toString(StandardCharsets.UTF_8)
            }
            check("\"version\": \"$releaseVersion\"" in fabricMetadata) {
                "Runtime metadata does not declare alpha version $releaseVersion"
            }
            check("\"license\": \"$projectLicenseId\"" in fabricMetadata) {
                "Runtime metadata must declare Apache-2.0"
            }

            val manifest = archive.getInputStream(archive.getEntry("META-INF/MANIFEST.MF")).use {
                it.readBytes().toString(StandardCharsets.UTF_8)
            }
            check("BlendLib-License: $projectLicenseId" in manifest) {
                "Runtime manifest is missing Apache-2.0 metadata"
            }
        }
    }
}

val verifyReleaseDocumentationArchives = tasks.register("verifyReleaseDocumentationArchives") {
    group = "verification"
    description = "Checks the aggregate source and Javadoc alpha archives cover all embedded runtime layers and legal files."
    dependsOn(releaseSourcesJar, releaseJavadocJar)

    doLast {
        ZipFile(releaseSourcesJarFile.get().asFile).use { sources ->
            val sourceNames = sources.entries().asSequence().map { it.name }.toSet()
            check(setOf("META-INF/LICENSE", "META-INF/NOTICE").all(sourceNames::contains)) {
                "Aggregate sources JAR must include LICENSE and NOTICE"
            }
            check(setOf(
                "com/liy/blendlib/api/BlendResourceId.java",
                "com/liy/blendlib/core/loader/ModelAssetLoader.java",
                "com/liy/blendlib/fabric/common/BlendLibCommonEntrypoint.java",
                "com/liy/blendlib/fabric/client/BlendLibClientEntrypoint.java",
            ).all(sourceNames::contains)) {
                "Aggregate sources JAR is missing an embedded runtime layer: $sourceNames"
            }
        }
        ZipFile(releaseJavadocJarFile.get().asFile).use { javadoc ->
            val javadocNames = javadoc.entries().asSequence().map { it.name }.toSet()
            check(setOf("META-INF/LICENSE", "META-INF/NOTICE").all(javadocNames::contains)) {
                "Aggregate Javadoc JAR must include LICENSE and NOTICE"
            }
            check("index.html" in javadocNames) { "Aggregate Javadoc JAR has no index.html" }
            check("com/liy/blendlib/api/BlendResourceId.html" in javadocNames) {
                "Aggregate Javadoc JAR has no public API documentation"
            }
        }
    }
}

extensions.configure<PublishingExtension> {
    publications {
        create<MavenPublication>("blendlibFabricAlpha") {
            groupId = rootProject.group.toString()
            artifactId = releaseArtifactId
            version = releaseVersion
            artifact(fabricRuntimeJar)
            artifact(releaseSourcesJar)
            artifact(releaseJavadocJar)
            pom {
                name.set("BlendLib Fabric 26.1.2 Public Alpha")
                description.set("Self-contained BlendLib Fabric public alpha runtime for Minecraft 26.1.2.")
                url.set(projectUrl)
                licenses {
                    license {
                        name.set(projectLicenseName)
                        url.set(projectLicenseUrl)
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("LIy-hub")
                        name.set("LIy-hub")
                        email.set("2734855720@qq.com")
                    }
                }
                scm {
                    connection.set("scm:git:$projectScmUrl")
                    developerConnection.set("scm:git:$projectScmUrl")
                    url.set(projectUrl)
                }
                withXml {
                    val dependencies = asNode().appendNode("dependencies")
                    val dependency = dependencies.appendNode("dependency")
                    dependency.appendNode("groupId", rootProject.group.toString())
                    dependency.appendNode("artifactId", "blendlib-api")
                    dependency.appendNode("version", releaseVersion)
                }
            }
        }
    }
    repositories {
        maven {
            name = "localBlendlibMaven"
            url = localMavenDirectory.get().asFile.toURI()
        }
    }
}

apiProject.extensions.configure<PublishingExtension> {
    publications {
        create<MavenPublication>("publicAlphaApi") {
            from(apiProject.components.getByName("java"))
            groupId = rootProject.group.toString()
            artifactId = "blendlib-api"
            version = releaseVersion
            pom {
                name.set("BlendLib API 26.1.2 Public Alpha compile surface")
                description.set("Public compile surface for the BlendLib Fabric alpha runtime.")
                url.set(projectUrl)
                licenses {
                    license {
                        name.set(projectLicenseName)
                        url.set(projectLicenseUrl)
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("LIy-hub")
                        name.set("LIy-hub")
                        email.set("2734855720@qq.com")
                    }
                }
                scm {
                    connection.set("scm:git:$projectScmUrl")
                    developerConnection.set("scm:git:$projectScmUrl")
                    url.set(projectUrl)
                }
            }
        }
    }
    repositories {
        maven {
            name = "localBlendlibMaven"
            url = localMavenDirectory.get().asFile.toURI()
        }
    }
}

val publishPublicAlpha = tasks.register("publishPublicAlpha") {
    group = "publishing"
    description = "Publishes the public alpha runtime and pure API compile surface to build/local-maven for verification."
    dependsOn(tasks.named("publish"), apiProject.tasks.named("publish"))
}

val localMavenPom = localMavenDirectory.map {
    it.file("com/liy/blendlib/$releaseArtifactId/$releaseVersion/$releaseArtifactId-$releaseVersion.pom")
}
val localMavenRuntimeJar = localMavenDirectory.map {
    it.file("com/liy/blendlib/$releaseArtifactId/$releaseVersion/$releaseArtifactId-$releaseVersion.jar")
}
val localMavenSourcesJar = localMavenDirectory.map {
    it.file("com/liy/blendlib/$releaseArtifactId/$releaseVersion/$releaseArtifactId-$releaseVersion-sources.jar")
}
val localMavenJavadocJar = localMavenDirectory.map {
    it.file("com/liy/blendlib/$releaseArtifactId/$releaseVersion/$releaseArtifactId-$releaseVersion-javadoc.jar")
}
val localMavenRuntimeMetadata = localMavenDirectory.map {
    it.file("com/liy/blendlib/$releaseArtifactId/maven-metadata.xml")
}
val localMavenApiPom = localMavenDirectory.map {
    it.file("com/liy/blendlib/blendlib-api/$releaseVersion/blendlib-api-$releaseVersion.pom")
}
val localMavenApiJar = localMavenDirectory.map {
    it.file("com/liy/blendlib/blendlib-api/$releaseVersion/blendlib-api-$releaseVersion.jar")
}
val localMavenApiSourcesJar = localMavenDirectory.map {
    it.file("com/liy/blendlib/blendlib-api/$releaseVersion/blendlib-api-$releaseVersion-sources.jar")
}
val localMavenApiModule = localMavenDirectory.map {
    it.file("com/liy/blendlib/blendlib-api/$releaseVersion/blendlib-api-$releaseVersion.module")
}
val localMavenApiMetadata = localMavenDirectory.map {
    it.file("com/liy/blendlib/blendlib-api/maven-metadata.xml")
}
val localMavenPrimaryArtifactProviders = listOf(
    localMavenRuntimeJar,
    localMavenSourcesJar,
    localMavenJavadocJar,
    localMavenPom,
    localMavenRuntimeMetadata,
    localMavenApiJar,
    localMavenApiSourcesJar,
    localMavenApiModule,
    localMavenApiPom,
    localMavenApiMetadata,
)

fun localMavenPrimaryArtifactFiles(): List<java.io.File> =
    localMavenPrimaryArtifactProviders.map { it.get().asFile }

// Primary public alpha artifacts are the files a local consumer can resolve or install. Maven's
// .md5/.sha1/.sha256/.sha512 files are derived sidecars and are deliberately verified as such
// by verifyLocalMavenPublication rather than treated as independently distributed artifacts.
val releasePrimaryArtifactProviders = listOf(
    releaseRuntimeJarFile,
    releaseShowcaseJarFile,
    releaseSourcesJarFile,
    releaseJavadocJarFile,
    blenderAddonZip,
) + localMavenPrimaryArtifactProviders

fun releasePrimaryArtifactFiles(): List<java.io.File> =
    releasePrimaryArtifactProviders.map { it.get().asFile }

val verifyLocalMavenPublication = tasks.register("verifyLocalMavenPublication") {
    group = "verification"
    description = "Verifies the local Maven alpha publication is self-contained and carries Apache metadata and legal files."
    dependsOn(publishPublicAlpha)

    doLast {
        val pomFile = localMavenPom.get().asFile
        check(pomFile.isFile) { "Expected local Maven POM: $pomFile" }
        val pom = pomFile.readText(StandardCharsets.UTF_8)
        check("<artifactId>$releaseArtifactId</artifactId>" in pom) { "Local POM has the wrong artifact id" }
        check("<version>$releaseVersion</version>" in pom) { "Local POM has the wrong alpha version" }
        check("<name>$projectLicenseName</name>" in pom) { "Local POM must declare the Apache 2.0 license" }
        check("<url>$projectUrl</url>" in pom && "<name>LIy-hub</name>" in pom &&
                "<email>2734855720@qq.com</email>" in pom && "<scm>" in pom) {
            "Local runtime POM must include project URL, author, email, and SCM metadata"
        }
        check("<artifactId>blendlib-api</artifactId>" in pom) {
            "The local runtime POM must expose the separately published pure API compile surface"
        }
        check("<artifactId>blendlib-core</artifactId>" !in pom
                && "<artifactId>blendlib-fabric-common</artifactId>" !in pom
                && "<artifactId>blendlib-fabric-client</artifactId>" !in pom) {
            "The self-contained runtime POM must not expose internal core/common/client modules"
        }
        val apiPomFile = localMavenApiPom.get().asFile
        check(apiPomFile.isFile) { "Expected local API POM: $apiPomFile" }
        val apiPom = apiPomFile.readText(StandardCharsets.UTF_8)
        check("<name>$projectLicenseName</name>" in apiPom && "<url>$projectUrl</url>" in apiPom &&
                "<name>LIy-hub</name>" in apiPom && "<email>2734855720@qq.com</email>" in apiPom &&
                "<scm>" in apiPom) {
            "Local API POM must include Apache, project, author, email, and SCM metadata"
        }

        val primaryArtifacts = localMavenPrimaryArtifactFiles()
        check(primaryArtifacts.all(java.io.File::isFile)) {
            "Expected local Maven primary artifacts: $primaryArtifacts"
        }
        primaryArtifacts.filter { it.extension.equals("jar", ignoreCase = true) }.forEach { artifact ->
            ZipFile(artifact).use { archive ->
                check(archive.getEntry("META-INF/LICENSE") != null && archive.getEntry("META-INF/NOTICE") != null) {
                    "Local Maven JAR must include LICENSE and NOTICE: $artifact"
                }
            }
        }
        val expectedPrimaryPaths = primaryArtifacts
            .map { it.toPath().toAbsolutePath().normalize().toString() }
            .toSortedSet()
        check(expectedPrimaryPaths.size == primaryArtifacts.size) {
            "Local Maven primary artifact definition contains duplicate paths: $primaryArtifacts"
        }

        val localRoot = localMavenDirectory.get().asFile
        val discoveredPrimaryPaths = localRoot.walkTopDown()
            .filter { it.isFile && (it.extension in setOf("jar", "pom", "module") || it.name == "maven-metadata.xml") }
            .map { it.toPath().toAbsolutePath().normalize().toString() }
            .toSortedSet()
        check(discoveredPrimaryPaths == expectedPrimaryPaths) {
            "Local Maven primary artifact set differs from the explicit alpha publication definition; " +
                    "missing=${expectedPrimaryPaths - discoveredPrimaryPaths} " +
                    "extra=${discoveredPrimaryPaths - expectedPrimaryPaths}"
        }

        val checksumSuffixes = listOf(".md5", ".sha1", ".sha256", ".sha512")
        val derivedChecksumFiles = localRoot.walkTopDown().filter { it.isFile }
            .filter { file -> checksumSuffixes.any(file.name::endsWith) }
            .toList()
        val allLocalMavenFiles = localRoot.walkTopDown().filter(java.io.File::isFile).toList()
        check(allLocalMavenFiles.size == primaryArtifacts.size + derivedChecksumFiles.size) {
            "Local Maven contains files outside the explicit primary artifacts and derived checksum sidecars: " +
                    allLocalMavenFiles.filter { it !in primaryArtifacts && it !in derivedChecksumFiles }
        }
        check(derivedChecksumFiles.all { sidecar ->
            val suffix = checksumSuffixes.first { sidecar.name.endsWith(it) }
            java.io.File(sidecar.parentFile, sidecar.name.removeSuffix(suffix)).isFile
        }) {
            "Every local Maven checksum sidecar must derive from an explicit primary artifact: $derivedChecksumFiles"
        }
    }
}

val dependencyInventoryFile = releaseDirectory.map { it.file("dependency-inventory.txt") }
val licenseInventoryFile = releaseDirectory.map { it.file("license-inventory.txt") }

/** A tabular, machine-verifiable record for a component visible to a local alpha consumer. */
data class ReleaseInventoryRow(
    val scope: String,
    val coordinate: String,
    val version: String,
    val inclusion: String,
    val source: String,
    val license: String,
    val licenseEvidence: String,
    val notice: String,
    val noticeEvidence: String,
    val boundedAbsence: String,
) {
    fun key(): String = listOf(scope, coordinate, version).joinToString("\t")
}

val dependencyInventoryFormat = "blendlib-release-dependency-inventory-v2"
val licenseInventoryFormat = "blendlib-release-license-inventory-v2"
val licenseInventoryColumns = listOf(
    "scope",
    "coordinate",
    "version",
    "inclusion",
    "source",
    "license",
    "license_evidence",
    "notice",
    "notice_evidence",
    "bounded_absence",
)

fun inventoryField(value: String): String = value
    .replace(Regex("[\\t\\r\\n]+"), " ")
    .trim()
    .also { check(it.isNotEmpty()) { "Release inventory fields must not be blank" } }

fun releaseRelativePath(file: java.io.File): String = rootProject.projectDir.toPath()
    .toAbsolutePath()
    .normalize()
    .relativize(file.toPath().toAbsolutePath().normalize())
    .toString()
    .replace('\\', '/')

fun archiveEntryNames(file: java.io.File): Set<String> = ZipFile(file).use { archive ->
    archive.entries().asSequence().map { it.name }.toSortedSet()
}

fun archiveEntryText(file: java.io.File, entryName: String): String = ZipFile(file).use { archive ->
    val entry = checkNotNull(archive.getEntry(entryName)) { "Missing $entryName in $file" }
    archive.getInputStream(entry).use { it.readBytes().toString(StandardCharsets.UTF_8) }
}

fun nestedArchiveEntryNames(outerArchive: java.io.File, nestedEntryName: String): Set<String> = ZipFile(outerArchive).use { outer ->
    val nestedEntry = checkNotNull(outer.getEntry(nestedEntryName)) { "Missing $nestedEntryName in $outerArchive" }
    outer.getInputStream(nestedEntry).use { nestedStream ->
        java.util.zip.ZipInputStream(nestedStream).use { nestedArchive ->
            buildSet {
                while (true) {
                    val entry = nestedArchive.nextEntry ?: break
                    add(entry.name)
                }
            }
        }
    }
}

fun archiveNoticeEntries(file: java.io.File): List<String> {
    if (!file.isFile || !(file.name.endsWith(".jar", ignoreCase = true) || file.name.endsWith(".zip", ignoreCase = true))) {
        return emptyList()
    }
    val noticeFileName = Regex("(?i)^(license|notice|copying|copyright)([._-].*)?$")
    return archiveEntryNames(file)
        .filter { entry -> noticeFileName.matches(entry.substringAfterLast('/')) }
        .sorted()
}

fun resolvedRuntimeModuleComponents(): List<ModuleComponentIdentifier> = fabricClientProject
    .configurations
    .getByName("runtimeClasspath")
    .incoming
    .resolutionResult
    .allComponents
    .mapNotNull { component -> component.id as? ModuleComponentIdentifier }
    .distinctBy { component -> "${component.group}:${component.module}:${component.version}" }
    .sortedBy { component -> "${component.group}:${component.module}:${component.version}" }

fun cachedPomFiles(component: ModuleComponentIdentifier): List<java.io.File> {
    // Some local wrappers launch a daemon with a project-specific Gradle home while the resolved
    // POM cache remains under the current user's default .gradle directory.  Search both explicit
    // local cache roots in deterministic order, but never infer a license when neither contains a
    // POM declaration.
    val cacheHomes = listOf(
        gradle.gradleUserHomeDir,
        java.io.File(System.getProperty("user.home"), ".gradle"),
    ).map { it.toPath().toAbsolutePath().normalize().toFile() }
        .distinctBy { it.path.lowercase() }
        .sortedBy { it.path.lowercase() }
    val pomFiles = cacheHomes.flatMap { cacheHome ->
        val cacheRoot = java.io.File(cacheHome, "caches/modules-2/files-2.1")
        val versionDirectory = java.io.File(
            java.io.File(
                java.io.File(cacheRoot, component.group),
                component.module,
            ),
            component.version,
        )
        if (versionDirectory.isDirectory) {
            versionDirectory.walkTopDown()
                .filter { it.isFile && it.extension.equals("pom", ignoreCase = true) }
                .toList()
        } else {
            emptyList()
        }
    }.distinctBy { it.toPath().toAbsolutePath().normalize().toString().lowercase() }
        .sortedBy { it.toPath().toAbsolutePath().normalize().toString().lowercase() }
    return pomFiles
}

fun pomLicenseNames(pomFiles: List<java.io.File>): List<String> {
    val licensePattern = Regex("(?is)<license\\b[^>]*>.*?<name\\b[^>]*>\\s*(.*?)\\s*</name>.*?</license>")
    return pomFiles.flatMap { pom ->
        licensePattern.findAll(pom.readText(StandardCharsets.UTF_8))
            .map { match ->
                match.groupValues[1]
                    .replace(Regex("<[^>]+>"), "")
                    .replace(Regex("\\s+"), " ")
                    .trim()
            }
            .filter(String::isNotBlank)
            .toList()
    }.distinct().sorted()
}

fun staticReleaseInventoryRows(
    runtimeFile: java.io.File,
    runtimeEntries: Set<String>,
    showcaseEntries: Set<String>,
    javadocEntries: Set<String>,
    addonEntries: Set<String>,
): List<ReleaseInventoryRow> {
    val expectedNested = setOf(
        "META-INF/jars/blendlib-api-$releaseVersion.jar",
        "META-INF/jars/blendlib-core-$releaseVersion.jar",
        "META-INF/jars/blendlib-fabric-common-$releaseVersion.jar",
    )
    val nested = runtimeEntries.filter { it.startsWith("META-INF/jars/") && it.endsWith(".jar") }.toSet()
    check(nested == expectedNested) {
        "Runtime archive must contain exactly the expected local nested modules; actual=$nested"
    }
    check(runtimeEntries.filterNot { it.endsWith("/") }.all { entry ->
        entry == "fabric.mod.json" || entry == "assets/blendlib/icon.png" ||
                entry.startsWith("META-INF/") || entry.startsWith("com/liy/blendlib/")
    }) {
        "Outer runtime archive contains unaccounted non-BlendLib payload: $runtimeEntries"
    }
    ZipFile(runtimeFile).use { archive ->
        val iconEntry = checkNotNull(archive.getEntry("assets/blendlib/icon.png")) {
            "Outer runtime archive is missing assets/blendlib/icon.png"
        }
        check(!iconEntry.isDirectory && archive.getInputStream(iconEntry).use { it.readBytes().isNotEmpty() }) {
            "Outer runtime archive icon must be a non-empty file"
        }
    }
    val nestedNoticeEntries = expectedNested.associateWith { nestedEntry ->
        val nestedEntries = nestedArchiveEntryNames(runtimeFile, nestedEntry)
        check(nestedEntries.filterNot { it.endsWith("/") }.all { entry ->
            // Loom retains each included BlendLib module's own Fabric metadata; it is local
            // project metadata, not a third-party payload or a bypass of NOTICE scanning.
            entry == "fabric.mod.json" || entry.startsWith("META-INF/") || entry.startsWith("com/liy/blendlib/")
        }) {
            "Nested local module contains unaccounted payload: $nestedEntry -> $nestedEntries"
        }
        nestedEntries.filter { entry ->
            Regex("(?i)^(license|notice|copying|copyright)([._-].*)?$")
                .matches(entry.substringAfterLast('/'))
        }.sorted()
    }
    check(showcaseEntries.none { it.startsWith("META-INF/jars/") }) {
        "Showcase archive must not bundle a hidden third-party nested JAR"
    }
    val expectedJavadocLegal = setOf(
        "legal/COPYRIGHT",
        "legal/LICENSE",
        "legal/jquery.md",
        "legal/jqueryUI.md",
        "legal/dejavufonts.md",
    )
    check(expectedJavadocLegal.all(javadocEntries::contains)) {
        "Aggregate Javadoc is missing required legal entries; actual=$javadocEntries"
    }
    check("LICENSE" in addonEntries && "scripts/verify_p2_descriptor_schema.py" !in addonEntries) {
        "Add-on package must include its GPL text and exclude the jsonschema fixture helper"
    }

    fun nestedNotice(nestedEntry: String): String =
        if (nestedNoticeEntries.getValue(nestedEntry).isEmpty()) "ABSENT" else "PRESENT"
    fun nestedNoticeEvidence(nestedEntry: String): String {
        val entries = nestedNoticeEntries.getValue(nestedEntry)
        return if (entries.isNotEmpty()) {
            entries.joinToString(prefix = "PRESENT:$nestedEntry:", separator = ",")
        } else {
            "ABSENT:no LICENSE/NOTICE/COPYING/COPYRIGHT entry in nested local module"
        }
    }

    return listOf(
        ReleaseInventoryRow(
            "packaged-local", "$group:$releaseArtifactId", releaseVersion,
            "bundled:release-runtime", "runtime fabric.mod.json + assets/blendlib/icon.png + outer archive",
            projectLicenseId, "fabric.mod.json + META-INF/MANIFEST.MF + META-INF/LICENSE",
            "PRESENT", "META-INF/NOTICE",
            "none",
        ),
        ReleaseInventoryRow(
            "packaged-local", "$group:blendlib-api", releaseVersion,
            "bundled:META-INF/jars/blendlib-api-$releaseVersion.jar", "runtime nested archive",
            projectLicenseId, "nested META-INF/LICENSE + outer Apache metadata",
            nestedNotice("META-INF/jars/blendlib-api-$releaseVersion.jar"),
            nestedNoticeEvidence("META-INF/jars/blendlib-api-$releaseVersion.jar"),
            "none",
        ),
        ReleaseInventoryRow(
            "packaged-local", "$group:blendlib-core", releaseVersion,
            "bundled:META-INF/jars/blendlib-core-$releaseVersion.jar", "runtime nested archive",
            projectLicenseId, "nested META-INF/LICENSE + outer Apache metadata",
            nestedNotice("META-INF/jars/blendlib-core-$releaseVersion.jar"),
            nestedNoticeEvidence("META-INF/jars/blendlib-core-$releaseVersion.jar"),
            "none",
        ),
        ReleaseInventoryRow(
            "packaged-local", "$group:blendlib-fabric-common", releaseVersion,
            "bundled:META-INF/jars/blendlib-fabric-common-$releaseVersion.jar", "runtime nested archive",
            projectLicenseId, "nested META-INF/LICENSE + outer Apache metadata",
            nestedNotice("META-INF/jars/blendlib-fabric-common-$releaseVersion.jar"),
            nestedNoticeEvidence("META-INF/jars/blendlib-fabric-common-$releaseVersion.jar"),
            "none",
        ),
        ReleaseInventoryRow(
            "packaged-local", "$group:blendlib-showcase", releaseVersion,
            "bundled:release-showcase", "showcase fabric.mod.json + archive",
            projectLicenseId, "showcase fabric.mod.json + META-INF/LICENSE",
            "PRESENT", "META-INF/NOTICE",
            "none",
        ),
        ReleaseInventoryRow(
            "packaged-addon", "$group:blendlib-exporter", "1.0.0",
            "bundled:Blender Add-on ZIP", "blender_manifest.toml",
            "GPL-3.0-or-later", "Add-on manifest SPDX declaration",
            "PRESENT", "ZIP:LICENSE",
            "none",
        ),
        ReleaseInventoryRow(
            "host-provided", "com.mojang:minecraft", "26.1.2",
            "not-bundled:Fabric launch host", "runtime fabric.mod.json depends.minecraft",
            "ABSENT", "not copied into the alpha archive",
            "ABSENT", "not copied into the alpha archive",
            "bounded:host game requirement; its terms/notices are not redistributed by this alpha",
        ),
        ReleaseInventoryRow(
            "host-provided", "org.openjdk:java", "25",
            "not-bundled:Fabric launch host", "runtime fabric.mod.json depends.java",
            "ABSENT", "not copied into the alpha archive",
            "ABSENT", "not copied into the alpha archive",
            "bounded:host JDK requirement; the alpha does not redistribute a JDK",
        ),
        ReleaseInventoryRow(
            "packaged-javadoc", "jdk-doclet:oracle-javadoc-assets", "25",
            "bundled:aggregate Javadoc", "Javadoc legal/LICENSE + legal/COPYRIGHT",
            "Oracle No-Fee Terms and Conditions", "Javadoc:legal/LICENSE",
            "PRESENT", "Javadoc:legal/LICENSE,legal/COPYRIGHT",
            "none",
        ),
        ReleaseInventoryRow(
            "packaged-javadoc", "jdk-doclet:jquery", "3.7.1",
            "bundled:aggregate Javadoc", "Javadoc legal/jquery.md",
            "MIT", "Javadoc:legal/jquery.md",
            "PRESENT", "Javadoc:legal/jquery.md",
            "none",
        ),
        ReleaseInventoryRow(
            "packaged-javadoc", "jdk-doclet:jquery-ui", "1.14.1",
            "bundled:aggregate Javadoc", "Javadoc legal/jqueryUI.md",
            "MIT", "Javadoc:legal/jqueryUI.md",
            "PRESENT", "Javadoc:legal/jqueryUI.md",
            "none",
        ),
        ReleaseInventoryRow(
            "packaged-javadoc", "jdk-doclet:dejavu-fonts", "2.37",
            "bundled:aggregate Javadoc", "Javadoc legal/dejavufonts.md",
            "DejaVu Fonts License", "Javadoc:legal/dejavufonts.md",
            "PRESENT", "Javadoc:legal/dejavufonts.md",
            "none",
        ),
    )
}

fun hostRuntimeInventoryRows(): List<ReleaseInventoryRow> {
    val runtimeConfiguration = fabricClientProject.configurations.getByName("runtimeClasspath")
    val artifactsByCoordinate = runtimeConfiguration.resolvedConfiguration.resolvedArtifacts
        .groupBy { artifact ->
            val module = artifact.moduleVersion.id
            "${module.group}:${module.name}:${module.version}"
        }

    return resolvedRuntimeModuleComponents().map { component ->
        val coordinate = "${component.group}:${component.module}"
        val componentKey = "$coordinate:${component.version}"
        val poms = cachedPomFiles(component)
        val declaredLicenses = pomLicenseNames(poms)
        val legalEntries = artifactsByCoordinate[componentKey].orEmpty()
            .flatMap { artifact -> archiveNoticeEntries(artifact.file).map { entry -> "${artifact.file.name}:$entry" } }
            .distinct()
            .sorted()
        val embeddedLicenseEntries = legalEntries.filter { entry ->
            Regex("(?i)^(license|copying)([._-].*)?$").matches(entry.substringAfterLast(':'))
        }
        val embeddedNoticeEntries = legalEntries.filter { entry ->
            Regex("(?i)^(notice|copyright)([._-].*)?$").matches(entry.substringAfterLast(':'))
        }
        val directFabricDependency = coordinate == "net.fabricmc:fabric-loader" ||
                coordinate == "net.fabricmc.fabric-api:fabric-api"
        val source = buildString {
            append(if (directFabricDependency) "runtime fabric.mod.json direct dependency; " else "")
            append("Gradle runtimeClasspath resolution")
            append(if (poms.isEmpty()) "; local POM absent" else "; local POM present")
            append(if (artifactsByCoordinate[componentKey].isNullOrEmpty()) "; resolved artifact absent" else "; resolved artifact present")
        }
        val license = when {
            declaredLicenses.isNotEmpty() -> declaredLicenses.joinToString("; ")
            embeddedLicenseEntries.isNotEmpty() -> "UNCLASSIFIED-EMBEDDED-LICENSE"
            else -> "ABSENT"
        }
        val licenseEvidence = when {
            declaredLicenses.isNotEmpty() -> "PRESENT:scanned local POM license declaration"
            embeddedLicenseEntries.isNotEmpty() -> "PRESENT:${embeddedLicenseEntries.joinToString(",")}"
            else -> "ABSENT:no <license><name> declaration in scanned local POM or embedded LICENSE/COPYING entry"
        }
        val notice = if (embeddedNoticeEntries.isEmpty()) "ABSENT" else "PRESENT"
        val noticeEvidence = if (embeddedNoticeEntries.isEmpty()) {
            "ABSENT:no NOTICE/COPYRIGHT entry in resolved artifact"
        } else {
            "PRESENT:${embeddedNoticeEntries.joinToString(",")}"
        }
        val boundedAbsence = if (license == "ABSENT" || notice == "ABSENT") {
            "bounded:not bundled in the alpha archive; only local Gradle POM/resolved-artifact metadata was scanned"
        } else {
            "none"
        }
        ReleaseInventoryRow(
            "host-runtime", coordinate, component.version,
            "not-bundled:Fabric/Minecraft launch classpath", source,
            license, licenseEvidence, notice, noticeEvidence, boundedAbsence,
        )
    }
}

fun expectedStaticInventoryKeys(): Set<String> = setOf(
    "packaged-local\t$group:$releaseArtifactId\t$releaseVersion",
    "packaged-local\t$group:blendlib-api\t$releaseVersion",
    "packaged-local\t$group:blendlib-core\t$releaseVersion",
    "packaged-local\t$group:blendlib-fabric-common\t$releaseVersion",
    "packaged-local\t$group:blendlib-showcase\t$releaseVersion",
    "packaged-addon\t$group:blendlib-exporter\t1.0.0",
    "host-provided\tcom.mojang:minecraft\t26.1.2",
    "host-provided\torg.openjdk:java\t25",
    "packaged-javadoc\tjdk-doclet:oracle-javadoc-assets\t25",
    "packaged-javadoc\tjdk-doclet:jquery\t3.7.1",
    "packaged-javadoc\tjdk-doclet:jquery-ui\t1.14.1",
    "packaged-javadoc\tjdk-doclet:dejavu-fonts\t2.37",
)

fun expectedReleaseInventoryKeys(): Set<String> = expectedStaticInventoryKeys() +
        resolvedRuntimeModuleComponents().map { component ->
            "host-runtime\t${component.group}:${component.module}\t${component.version}"
        }

fun renderLicenseInventory(rows: List<ReleaseInventoryRow>): String = buildString {
    appendLine("format=$licenseInventoryFormat")
    appendLine("release_version=$releaseVersion")
    appendLine("columns=${licenseInventoryColumns.joinToString("\t")}")
    rows.sortedWith(compareBy(ReleaseInventoryRow::scope, ReleaseInventoryRow::coordinate, ReleaseInventoryRow::version))
        .forEach { row ->
            appendLine(listOf(
                "entry", row.scope, row.coordinate, row.version, row.inclusion, row.source,
                row.license, row.licenseEvidence, row.notice, row.noticeEvidence, row.boundedAbsence,
            ).joinToString("\t") { inventoryField(it) })
        }
}

fun renderDependencyInventory(rows: List<ReleaseInventoryRow>, primaryArtifacts: List<java.io.File>): String = buildString {
    appendLine("format=$dependencyInventoryFormat")
    appendLine("release_version=$releaseVersion")
    appendLine("artifact_columns=path\tclassification")
    primaryArtifacts.sortedBy(::releaseRelativePath).forEach { artifact ->
        appendLine(listOf(
            "artifact",
            releaseRelativePath(artifact),
            if (artifact.toPath().startsWith(localMavenDirectory.get().asFile.toPath())) {
                "local-maven-primary"
            } else {
                "local-release-primary"
            },
        ).joinToString("\t") { inventoryField(it) })
    }
    appendLine("component_columns=scope\tcoordinate\tversion\tinclusion\tsource")
    rows.sortedWith(compareBy(ReleaseInventoryRow::scope, ReleaseInventoryRow::coordinate, ReleaseInventoryRow::version))
        .forEach { row ->
            appendLine(listOf(
                "component", row.scope, row.coordinate, row.version, row.inclusion, row.source,
            ).joinToString("\t") { inventoryField(it) })
        }
}

fun parseLicenseInventory(file: java.io.File): List<ReleaseInventoryRow> {
    val lines = file.readLines(StandardCharsets.UTF_8)
    check(lines.size >= 4) { "License inventory is incomplete: $file" }
    check(lines[0] == "format=$licenseInventoryFormat") { "Unexpected license inventory format in $file" }
    check(lines[1] == "release_version=$releaseVersion") { "Unexpected license inventory release version in $file" }
    check(lines[2] == "columns=${licenseInventoryColumns.joinToString("\t")}") { "Malformed license inventory columns in $file" }
    return lines.drop(3).mapIndexed { index, line ->
        val fields = line.split('\t')
        check(fields.size == 11 && fields.first() == "entry") {
            "Malformed license inventory entry at ${index + 4}: $line"
        }
        check(fields.drop(1).all(String::isNotBlank)) {
            "Blank license inventory field at ${index + 4}: $line"
        }
        ReleaseInventoryRow(
            fields[1], fields[2], fields[3], fields[4], fields[5], fields[6], fields[7],
            fields[8], fields[9], fields[10],
        )
    }
}

fun parseDependencyInventory(file: java.io.File): Pair<Set<String>, Set<String>> {
    val lines = file.readLines(StandardCharsets.UTF_8)
    check(lines.size >= 6) { "Dependency inventory is incomplete: $file" }
    check(lines[0] == "format=$dependencyInventoryFormat") { "Unexpected dependency inventory format in $file" }
    check(lines[1] == "release_version=$releaseVersion") { "Unexpected dependency inventory release version in $file" }
    check(lines[2] == "artifact_columns=path\tclassification") { "Malformed dependency artifact columns in $file" }
    val componentHeaderIndex = lines.indexOf("component_columns=scope\tcoordinate\tversion\tinclusion\tsource")
    check(componentHeaderIndex > 3) { "Missing dependency component columns in $file" }
    val artifactPaths = lines.subList(3, componentHeaderIndex).mapIndexed { index, line ->
        val fields = line.split('\t')
        check(fields.size == 3 && fields.first() == "artifact" && fields.drop(1).all(String::isNotBlank)) {
            "Malformed dependency artifact entry at ${index + 4}: $line"
        }
        fields[1]
    }.toSet()
    val componentKeys = lines.drop(componentHeaderIndex + 1).mapIndexed { index, line ->
        val fields = line.split('\t')
        check(fields.size == 6 && fields.first() == "component" && fields.drop(1).all(String::isNotBlank)) {
            "Malformed dependency component entry at ${componentHeaderIndex + index + 2}: $line"
        }
        listOf(fields[1], fields[2], fields[3]).joinToString("\t")
    }.toSet()
    check(artifactPaths.size == componentHeaderIndex - 3) { "Duplicate dependency artifact path in $file" }
    check(componentKeys.size == lines.size - componentHeaderIndex - 1) { "Duplicate dependency component key in $file" }
    return artifactPaths to componentKeys
}

fun verifyReleaseInventoryFiles(licenseFile: java.io.File, dependencyFile: java.io.File) {
    val licenseRows = parseLicenseInventory(licenseFile)
    val licenseKeys = licenseRows.map(ReleaseInventoryRow::key)
    check(licenseKeys.toSet().size == licenseRows.size) { "Duplicate license inventory component record" }
    check(licenseKeys.toSet() == expectedReleaseInventoryKeys()) {
        "License inventory is incomplete or contains unexpected components; " +
                "missing=${expectedReleaseInventoryKeys() - licenseKeys.toSet()} " +
                "extra=${licenseKeys.toSet() - expectedReleaseInventoryKeys()}"
    }
    licenseRows.forEach { row ->
        check(row.license != "ABSENT" || row.boundedAbsence.startsWith("bounded:")) {
            "License absence must be explicitly bounded for ${row.key()}"
        }
        check(row.notice != "ABSENT" || row.boundedAbsence.startsWith("bounded:")) {
            "NOTICE absence must be explicitly bounded for ${row.key()}"
        }
        check(row.licenseEvidence.isNotBlank() && row.noticeEvidence.isNotBlank()) {
            "License/NOTICE evidence must be recorded for ${row.key()}"
        }
    }

    val (artifactPaths, dependencyKeys) = parseDependencyInventory(dependencyFile)
    val expectedArtifactPaths = releasePrimaryArtifactFiles().map(::releaseRelativePath).toSet()
    check(artifactPaths == expectedArtifactPaths) {
        "Dependency inventory artifact set is incomplete or unexpected; " +
                "missing=${expectedArtifactPaths - artifactPaths} extra=${artifactPaths - expectedArtifactPaths}"
    }
    check(dependencyKeys == licenseKeys.toSet()) {
        "Dependency and license inventories disagree about component coverage; " +
                "dependencyOnly=${dependencyKeys - licenseKeys.toSet()} " +
                "licenseOnly=${licenseKeys.toSet() - dependencyKeys}"
    }
}

val generateReleaseInventories = tasks.register("generateReleaseInventories") {
    group = "distribution"
    description = "Writes archive-scoped public alpha dependency, license, and NOTICE availability inventories."
    dependsOn(
        // verifyRuntimeArchive and verifyReleaseDocumentationArchives cover the other release
        // copies, but Showcase has no archive verifier of its own.  Make its copy an explicit
        // predecessor so a clean root build never inventories before this primary artifact exists.
        releaseShowcaseJar,
        verifyRuntimeArchive,
        verifyReleaseDocumentationArchives,
        verifyLocalMavenPublication,
        verifyBlenderAddonPackage,
    )
    inputs.files(fabricClientProject.configurations.getByName("runtimeClasspath"))
    inputs.files(*releasePrimaryArtifactProviders.toTypedArray())
    inputs.property("releaseInventoryFormat", "$dependencyInventoryFormat/$licenseInventoryFormat")
    inputs.property("runtimeComponentCoordinates", providers.provider {
        resolvedRuntimeModuleComponents().joinToString("\n") { component ->
            "${component.group}:${component.module}:${component.version}"
        }
    })
    outputs.files(dependencyInventoryFile, licenseInventoryFile)

    doLast {
        val primaryArtifacts = releasePrimaryArtifactFiles()
        check(primaryArtifacts.all(java.io.File::isFile)) { "Missing primary release artifact: $primaryArtifacts" }
        check(primaryArtifacts.map { it.toPath().toAbsolutePath().normalize() }.toSet().size == primaryArtifacts.size) {
            "Primary release artifact definition contains duplicate paths: $primaryArtifacts"
        }
        val runtimeFile = releaseRuntimeJarFile.get().asFile
        val showcaseFile = releaseShowcaseJarFile.get().asFile
        val javadocFile = releaseJavadocJarFile.get().asFile
        val addonFile = blenderAddonZip.get().asFile
        verifyBlenderAddonLicenseContract(addonFile)
        val staticRows = staticReleaseInventoryRows(
            runtimeFile,
            archiveEntryNames(runtimeFile),
            archiveEntryNames(showcaseFile),
            archiveEntryNames(javadocFile),
            archiveEntryNames(addonFile),
        )
        check("Oracle No-Fee Terms and Conditions" in archiveEntryText(javadocFile, "legal/LICENSE")) {
            "Javadoc legal/LICENSE no longer identifies the documented Oracle terms"
        }
        check("jQuery v3.7.1" in archiveEntryText(javadocFile, "legal/jquery.md") &&
                "jQuery UI v1.14.1" in archiveEntryText(javadocFile, "legal/jqueryUI.md") &&
                "DejaVu fonts v2.37" in archiveEntryText(javadocFile, "legal/dejavufonts.md")) {
            "Javadoc bundled third-party legal records no longer match the inventory"
        }
        ZipFile(addonFile).use { archive ->
            val packagedPython = archive.entries().asSequence()
                .filter { it.name.endsWith(".py") }
                .map { entry -> archive.getInputStream(entry).use { it.readBytes().toString(StandardCharsets.UTF_8) } }
                .joinToString("\n")
            check("jsonschema" !in packagedPython) {
                "Blender Add-on ZIP must not ship the undeclared jsonschema fixture helper"
            }
        }
        val rows = staticRows + hostRuntimeInventoryRows()
        check(rows.map(ReleaseInventoryRow::key).toSet().size == rows.size) {
            "Release license inventory would contain duplicate component records: $rows"
        }
        check(rows.map(ReleaseInventoryRow::key).toSet() == expectedReleaseInventoryKeys()) {
            "Release license inventory does not cover exactly the static and resolved host components"
        }
        dependencyInventoryFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(renderDependencyInventory(rows, primaryArtifacts), StandardCharsets.UTF_8)
        }
        licenseInventoryFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(renderLicenseInventory(rows), StandardCharsets.UTF_8)
        }
    }
}

val verifyReleaseInventories = tasks.register("verifyReleaseInventories") {
    group = "verification"
    description = "Rejects incomplete, duplicate, malformed, or archive-inconsistent alpha inventory records."
    dependsOn(generateReleaseInventories)
    inputs.files(dependencyInventoryFile, licenseInventoryFile)
    inputs.files(*releasePrimaryArtifactProviders.toTypedArray())
    inputs.files(fabricClientProject.configurations.getByName("runtimeClasspath"))
    doLast {
        verifyReleaseInventoryFiles(licenseInventoryFile.get().asFile, dependencyInventoryFile.get().asFile)
    }
}

val verifyReleaseInventoryNegativeFixtures = tasks.register("verifyReleaseInventoryNegativeFixtures") {
    group = "verification"
    description = "Proves inventory verification rejects malformed, duplicate, and incomplete generated records."
    dependsOn(generateReleaseInventories)
    doLast {
        val fixtureDirectory = layout.buildDirectory.dir("tmp/release-inventory-negative-fixtures").get().asFile
        fixtureDirectory.mkdirs()
        val validLicense = licenseInventoryFile.get().asFile.readText(StandardCharsets.UTF_8)
        val validDependency = dependencyInventoryFile.get().asFile.readText(StandardCharsets.UTF_8)
        fun writeFixture(name: String, license: String = validLicense, dependency: String = validDependency): Pair<java.io.File, java.io.File> {
            val licenseFile = java.io.File(fixtureDirectory, "$name-license.txt")
            val dependencyFile = java.io.File(fixtureDirectory, "$name-dependency.txt")
            licenseFile.writeText(license, StandardCharsets.UTF_8)
            dependencyFile.writeText(dependency, StandardCharsets.UTF_8)
            return licenseFile to dependencyFile
        }
        fun expectRejected(name: String, files: Pair<java.io.File, java.io.File>) {
            check(runCatching { verifyReleaseInventoryFiles(files.first, files.second) }.isFailure) {
                "Inventory verifier unexpectedly accepted negative fixture: $name"
            }
        }

        expectRejected("malformed-license", writeFixture("malformed-license", license = "not-an-inventory\n"))
        val duplicateLicenseEntry = validLicense.lineSequence().first { it.startsWith("entry\t") }
        expectRejected("duplicate-license", writeFixture("duplicate-license", license = validLicense + duplicateLicenseEntry + "\n"))
        val omittedLicense = validLicense.lineSequence().filterNot { it.startsWith("entry\tpackaged-javadoc\tjdk-doclet:jquery\t") }
            .joinToString(System.lineSeparator(), postfix = System.lineSeparator())
        val omittedDependency = validDependency.lineSequence().filterNot {
            it.startsWith("component\tpackaged-javadoc\tjdk-doclet:jquery\t")
        }.joinToString(System.lineSeparator(), postfix = System.lineSeparator())
        expectRejected("incomplete-component", writeFixture("incomplete-component", omittedLicense, omittedDependency))
        val malformedDependency = validDependency.replaceFirst("artifact\t", "artifact\t\t")
        expectRejected("malformed-dependency", writeFixture("malformed-dependency", dependency = malformedDependency))
    }
}

fun sha256(file: java.io.File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) {
                break
            }
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

val sha256SumsFile = releaseDirectory.map { it.file("SHA256SUMS") }
val releaseHashArtifactProviders = listOf(
    releaseRuntimeJarFile,
    releaseShowcaseJarFile,
    releaseSourcesJarFile,
    releaseJavadocJarFile,
    blenderAddonZip,
    dependencyInventoryFile,
    licenseInventoryFile,
)

fun releaseHashArtifactFiles(): List<java.io.File> =
    releaseHashArtifactProviders.map { it.get().asFile }

fun releaseSha256RelativePath(file: java.io.File): String {
    val releaseRoot = releaseDirectory.get().asFile.toPath().toAbsolutePath().normalize()
    val artifactPath = file.toPath().toAbsolutePath().normalize()
    check(artifactPath.startsWith(releaseRoot)) {
        "Public SHA-256 artifact must be inside build/release: $artifactPath"
    }
    val relativePath = releaseRoot.relativize(artifactPath).toString().replace('\\', '/')
    check(relativePath.isNotBlank() && !relativePath.startsWith('/') &&
            !Regex("^[A-Za-z]:").containsMatchIn(relativePath)) {
        "Public SHA-256 artifact path must be relative: $relativePath"
    }
    check(relativePath.split('/').none { it.isBlank() || it == "." || it == ".." }) {
        "Public SHA-256 artifact path contains an unsafe segment: $relativePath"
    }
    return relativePath
}

val writeReleaseSha256 = tasks.register("writeReleaseSha256") {
    group = "distribution"
    description = "Records SHA-256 for the seven public alpha release files."
    dependsOn(
        verifyRuntimeArchive,
        verifyReleaseDocumentationArchives,
        verifyLocalMavenPublication,
        verifyBlenderAddonPackage,
        generateReleaseInventories,
        verifyReleaseInventories,
        verifyReleaseInventoryNegativeFixtures,
    )
    // Keep SHA256SUMS invalidated whenever any exact listed artifact changes.
    // This list is intentionally separate from the 26.2 spike, which remains a
    // standalone compatibility experiment rather than a 26.1.2 alpha bundle input.
    inputs.files(*releaseHashArtifactProviders.toTypedArray())
    inputs.property("releaseHashArtifactCount", releaseHashArtifactProviders.size)
    outputs.file(sha256SumsFile)

    doLast {
        val files = releaseHashArtifactFiles()
        check(files.isNotEmpty()) { "Release SHA-256 artifact definition must not be empty" }
        check(files.map { it.toPath().toAbsolutePath().normalize() }.toSet().size == files.size) {
            "SHA-256 artifact inputs must not contain duplicate paths: $files"
        }
        check(files.all(java.io.File::isFile)) { "Cannot hash a missing alpha artifact: $files" }
        val relativePaths = files.map(::releaseSha256RelativePath)
        check(relativePaths.toSet().size == relativePaths.size) {
            "SHA-256 artifact inputs must not contain duplicate release-relative paths: $relativePaths"
        }
        sha256SumsFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(files.zip(relativePaths).joinToString(
                separator = System.lineSeparator(),
                postfix = System.lineSeparator(),
            ) { (file, relativePath) -> "${sha256(file)}  $relativePath" }, StandardCharsets.UTF_8)
        }
    }
}

fun verifyReleaseSha256Contents() {
    val files = releaseHashArtifactFiles()
    check(files.isNotEmpty()) { "Release SHA-256 artifact definition must not be empty" }
    check(files.all(java.io.File::isFile)) { "Cannot verify a missing alpha artifact: $files" }

    val expectedPaths = files.map(::releaseSha256RelativePath)
    check(expectedPaths.toSet().size == expectedPaths.size) {
        "SHA-256 verification inputs must not contain duplicate paths: $expectedPaths"
    }

    val checksumFile = sha256SumsFile.get().asFile
    check(checksumFile.isFile) { "Expected SHA256SUMS file: $checksumFile" }
    val checksumLines = checksumFile.readLines(StandardCharsets.UTF_8).filter { it.isNotBlank() }
    check(checksumLines.size == files.size) {
        "SHA256SUMS must contain exactly ${files.size} non-blank entries, found ${checksumLines.size}"
    }

    val linePattern = Regex("^([0-9a-f]{64})  (.+)$")
    val recorded = linkedMapOf<String, String>()
    checksumLines.forEachIndexed { index, line ->
        val match = linePattern.matchEntire(line)
            ?: error("Malformed SHA256SUMS entry ${index + 1}: $line")
        val checksum = match.groupValues[1]
        val path = match.groupValues[2]
        check(path == path.trim()) { "SHA256SUMS entry ${index + 1} has surrounding path whitespace: $line" }
        check(!path.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(path) && '\\' !in path) {
            "SHA256SUMS entry ${index + 1} must use a stable relative forward-slash path: $path"
        }
        check(path.split('/').none { it.isBlank() || it == "." || it == ".." }) {
            "SHA256SUMS entry ${index + 1} contains an unsafe path segment: $path"
        }
        check(recorded.put(path, checksum) == null) {
            "SHA256SUMS contains a duplicate artifact path: $path"
        }
    }

    val expectedPathSet = expectedPaths.toSet()
    val missing = expectedPathSet - recorded.keys
    val extra = recorded.keys - expectedPathSet
    check(missing.isEmpty() && extra.isEmpty()) {
        "SHA256SUMS paths differ from the exact alpha artifact list; missing=$missing extra=$extra"
    }

    files.zip(expectedPaths).forEach { (file, path) ->
        val expectedHash = sha256(file)
        val recordedHash = recorded[path]
        check(recordedHash == expectedHash) {
            "SHA256SUMS mismatch for $path: recorded=$recordedHash actual=$expectedHash"
        }
    }
}

val verifyReleaseSha256 = tasks.register("verifyReleaseSha256") {
    group = "verification"
    description = "Verifies SHA256SUMS has exactly the declared primary public alpha artifacts and inventories."
    dependsOn(writeReleaseSha256)
    inputs.file(sha256SumsFile)
    inputs.files(*releaseHashArtifactProviders.toTypedArray())
    inputs.property("releaseHashArtifactCount", releaseHashArtifactProviders.size)
    doLast { verifyReleaseSha256Contents() }
}

val verifyReleaseSha256AtBuildReleaseEnd = tasks.register("verifyReleaseSha256AtBuildReleaseEnd") {
    group = "verification"
    description = "Re-verifies SHA256SUMS after the complete buildRelease lifecycle has finished."
    dependsOn(writeReleaseSha256)
    inputs.file(sha256SumsFile)
    inputs.files(*releaseHashArtifactProviders.toTypedArray())
    inputs.property("releaseHashArtifactCount", releaseHashArtifactProviders.size)
    doLast { verifyReleaseSha256Contents() }
}

val localMavenConsumerDirectory = layout.projectDirectory.dir("blendlib-local-maven-consumer-fixture")
val verifyLocalMavenConsumer = tasks.register<Exec>("verifyLocalMavenConsumer") {
    group = "verification"
    description = "Builds the blank consumer as an independent Gradle project against build/local-maven."
    dependsOn(publishPublicAlpha)
    commandLine(
        layout.projectDirectory.file("gradlew.bat").asFile.absolutePath,
        "-p", localMavenConsumerDirectory.asFile.absolutePath,
        "-Pblendlib_local_maven_repo=${localMavenDirectory.get().asFile.absolutePath}",
        "-Pblendlib_alpha_version=$releaseVersion",
        "clean",
        "check",
    )
}

val buildPublicAlpha = tasks.register("buildPublicAlpha") {
    group = "build"
    description = "Builds and verifies all BlendLib 1.0.0-alpha.1 public alpha artifacts."
    dependsOn(
        verifyReleaseSha256,
        verifyLocalMavenConsumer,
    )
}

tasks.named("buildRelease") {
    description = "Builds BlendLib modules, consumer fixtures, and the public alpha artifact set."
    dependsOn(buildPublicAlpha)
    // A finalizer runs after buildRelease and every direct dependency, rather
    // than racing an unrelated packaging task earlier in the lifecycle graph.
    finalizedBy(verifyReleaseSha256AtBuildReleaseEnd)
}
