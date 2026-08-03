import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile
import org.gradle.api.tasks.bundling.Jar

description = "Pure Java 25 BlendLib core skeleton."

dependencies {
    api(project(":blendlib-api"))
}

val verifyCoreJarBoundary = tasks.register("verifyCoreJarBoundary") {
    group = "verification"
    description = "Rejects Minecraft/Fabric class references from the assembled pure core JAR."
    dependsOn(tasks.named<Jar>("jar"))

    doLast {
        val jarFile = tasks.named<Jar>("jar").get().archiveFile.get().asFile
        val forbidden = listOf("net/minecraft/", "net/fabricmc/", "net.minecraft.", "net.fabricmc.")
        ZipFile(jarFile).use { archive ->
            val entries = archive.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) {
                    continue
                }
                check(forbidden.none(entry.name::contains)) {
                    "Core JAR contains a forbidden platform path: ${entry.name}"
                }
                if (entry.name.endsWith(".class")) {
                    val bytes = archive.getInputStream(entry).use { it.readBytes() }
                    val constantPoolText = bytes.toString(StandardCharsets.ISO_8859_1)
                    check(forbidden.none(constantPoolText::contains)) {
                        "Core JAR class references a forbidden platform type: ${entry.name}"
                    }
                }
            }
        }
    }
}

tasks.named("check") {
    dependsOn(verifyCoreJarBoundary)
}
