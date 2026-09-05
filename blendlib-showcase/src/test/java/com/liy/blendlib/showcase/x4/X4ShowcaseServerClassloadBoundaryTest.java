package com.liy.blendlib.showcase.x4;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import net.fabricmc.api.ModInitializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the common/Showcase server-boundary proof from Showcase, whose test task guarantees its
 * own production classes are available without adding a client-test task dependency.
 */
class X4ShowcaseServerClassloadBoundaryTest {
    private static final String API_MARKER = "com.liy.blendlib.api.BlendResourceId";
    private static final String CORE_MARKER = "com.liy.blendlib.core.BlendCoreService";
    private static final String COMMON_ENTRYPOINT = "com.liy.blendlib.fabric.common.BlendLibCommonEntrypoint";
    private static final String SHOWCASE_ENTRYPOINT = "com.liy.blendlib.showcase.BlendLibShowcaseEntrypoint";
    private static final String API_NAMESPACE = "com.liy.blendlib.api.";
    private static final String CORE_NAMESPACE = "com.liy.blendlib.core.";
    private static final String COMMON_NAMESPACE = "com.liy.blendlib.fabric.common.";
    private static final String SHOWCASE_NAMESPACE = "com.liy.blendlib.showcase.";
    private static final String CLIENT_HOST_INTERNAL = "com/liy/blendlib/fabric/client/";
    private static final String MINECRAFT_CLIENT_INTERNAL = "net/minecraft/client/";
    private static final String BLAZE3D_INTERNAL = "com/mojang/blaze3d/";
    private static final String FABRIC_CLIENT_INTERNAL = "net/fabricmc/fabric/api/client/";

    @Test
    void actualCommonAndShowcaseEntrypointsInitializeInAnIsolatedClientBlockingLoader() {
        List<ProductionLocation> locations = List.of(
                productionLocation(API_MARKER),
                productionLocation(CORE_MARKER),
                productionLocation(COMMON_ENTRYPOINT),
                productionLocation(SHOWCASE_ENTRYPOINT));
        try (ClientBlockingLoader loader = new ClientBlockingLoader(locations)) {
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass("net.minecraft.client.Minecraft"));
            assertThrows(ClassNotFoundException.class,
                    () -> loader.loadClass("com.liy.blendlib.fabric.client.host.X4HostAdapter"));
            assertThrows(ClassNotFoundException.class,
                    () -> loader.loadClass("net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry"));
            assertThrows(ClassNotFoundException.class,
                    () -> loader.loadClass("net.fabricmc.api.ClientModInitializer"));
            assertThrows(ClassNotFoundException.class,
                    () -> loader.loadClass("com.mojang.blaze3d.vertex.PoseStack"));
            initializeEntrypoint(loader, COMMON_ENTRYPOINT, true);
            initializeEntrypoint(loader, SHOWCASE_ENTRYPOINT, false);
        } catch (IOException exception) {
            throw new AssertionError("Unable to close the Showcase X4 boundary loader", exception);
        }
    }

    @Test
    void missingInspectedProductionLocationCannotFallBackToTheParentClasspath() throws IOException {
        List<ProductionLocation> locations = List.of(
                productionLocation(API_MARKER),
                productionLocation(CORE_MARKER),
                productionLocation(COMMON_ENTRYPOINT));
        try (ClientBlockingLoader loader = new ClientBlockingLoader(locations)) {
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass(SHOWCASE_ENTRYPOINT));
        }
    }

    @Test
    void loaderRejectsAnEmptyProductionUrlBeforeItCanReachTheParentClasspath() throws Exception {
        assertThrows(AssertionError.class,
                () -> new ClientBlockingLoader(List.of(new ProductionLocation(API_MARKER, new URL("file:")))));
    }

    @Test
    void codeSourceValidationFailsClosedForMissingUnsupportedAndNonProductionLocations(@TempDir Path temporary)
            throws Exception {
        assertThrows(AssertionError.class, () -> validatedProductionLocation(API_MARKER, null));
        assertThrows(AssertionError.class, () -> validatedProductionLocation(API_MARKER, new URL("file:")));
        assertThrows(AssertionError.class,
                () -> validatedProductionLocation(API_MARKER, new URL("https://example.invalid/production.jar")));
        assertThrows(AssertionError.class,
                () -> validatedProductionLocation(API_MARKER, temporary.resolve("missing/build/classes/java/main").toUri().toURL()));

        Path emptyProductionDirectory = productionDirectory(temporary.resolve("empty"), API_MARKER);
        Files.createDirectories(emptyProductionDirectory);
        assertThrows(AssertionError.class,
                () -> validatedProductionLocation(API_MARKER, emptyProductionDirectory.toUri().toURL()));

        Path wrongMarkerDirectory = productionDirectory(temporary.resolve("wrong-marker"), API_MARKER);
        writeClass(wrongMarkerDirectory, "com/example/Unrelated.class", new byte[] {0});
        assertThrows(AssertionError.class,
                () -> validatedProductionLocation(API_MARKER, wrongMarkerDirectory.toUri().toURL()));

        Path testOutputDirectory = temporary.resolve("test-output/blendlib-api/build/classes/java/test");
        writeClass(testOutputDirectory, markerResource(API_MARKER), markerBytes(API_MARKER));
        assertThrows(AssertionError.class,
                () -> validatedProductionLocation(API_MARKER, testOutputDirectory.toUri().toURL()));

        Path anotherModuleOutput = temporary.resolve("another-module/blendlib-core/build/classes/java/main");
        writeClass(anotherModuleOutput, markerResource(API_MARKER), markerBytes(API_MARKER));
        assertThrows(AssertionError.class,
                () -> validatedProductionLocation(API_MARKER, anotherModuleOutput.toUri().toURL()));

        Path unsupportedFile = temporary.resolve("unsupported-production-source.txt");
        Files.write(unsupportedFile, markerBytes(API_MARKER));
        assertThrows(AssertionError.class,
                () -> validatedProductionLocation(API_MARKER, unsupportedFile.toUri().toURL()));
    }

    @Test
    void validatedProductionDirectoryAndJarSupportPercentEncodedSpacePaths(@TempDir Path temporary) throws Exception {
        Path directory = productionDirectory(temporary.resolve("production directory with spaces"), API_MARKER);
        writeClass(directory, markerResource(API_MARKER), markerBytes(API_MARKER));
        ProductionLocation directoryLocation = validatedProductionLocation(API_MARKER, directory.toUri().toURL());
        assertTrue(directoryLocation.codeSource().toExternalForm().contains("%20"));
        assertLoadsFromTheValidatedLocation(directoryLocation);

        Path archive = temporary.resolve("production archive with spaces.jar");
        writeMarkerArchive(archive, API_MARKER);
        ProductionLocation archiveLocation = validatedProductionLocation(API_MARKER, archive.toUri().toURL());
        assertTrue(archiveLocation.codeSource().toExternalForm().contains("%20"));
        assertLoadsFromTheValidatedLocation(archiveLocation);
    }

    @Test
    void fullCommonAndShowcaseBytecodeScanContainsNoClientReference() throws IOException {
        for (ProductionLocation location : List.of(
                productionLocation(COMMON_ENTRYPOINT), productionLocation(SHOWCASE_ENTRYPOINT))) {
            scanProductionBytecode(location);
        }
    }

    private static void initializeEntrypoint(
            ClientBlockingLoader loader,
            String className,
            boolean invokeFabricCallback) {
        assertDoesNotThrow(() -> {
            Class<?> entrypointType = Class.forName(className, true, loader);
            Object instance = entrypointType.getDeclaredConstructor().newInstance();
            assertTrue(instance instanceof ModInitializer, className + " must remain a real Fabric common entrypoint");
            if (invokeFabricCallback) {
                ((ModInitializer) instance).onInitialize();
            }
            // Showcase performs mutable-registry registration in its callback. Class initialization
            // is the linkage proof here; the callback belongs only to Fabric's real registry lifecycle.
        }, className + " must class-initialize without resolving any client class");
    }

    private static ProductionLocation productionLocation(String className) {
        try {
            Class<?> type = Class.forName(className, false, X4ShowcaseServerClassloadBoundaryTest.class.getClassLoader());
            ProtectionDomain domain = type.getProtectionDomain();
            CodeSource source = domain == null ? null : domain.getCodeSource();
            if (source == null || source.getLocation() == null) {
                throw new AssertionError("Showcase test classpath has no production CodeSource for " + className);
            }
            return validatedProductionLocation(className, source.getLocation());
        } catch (ClassNotFoundException exception) {
            throw new AssertionError("Showcase test runtime is missing required production class " + className, exception);
        }
    }

    private static void scanProductionBytecode(ProductionLocation location) throws IOException {
        Path source = validatedProductionPath(location.className(), location.codeSource());
        int scanned = 0;
        if (Files.isDirectory(source)) {
            try (Stream<Path> classes = Files.walk(source)) {
                for (Path classFile : classes.filter(path -> path.toString().endsWith(".class")).toList()) {
                    assertNoClientReference(location, classFile.toString(), Files.readAllBytes(classFile));
                    scanned++;
                }
            }
        } else {
            try (JarFile archive = new JarFile(source.toFile())) {
                var entries = archive.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (!entry.isDirectory() && entry.getName().endsWith(".class")) {
                        try (var input = archive.getInputStream(entry)) {
                            assertNoClientReference(location, entry.getName(), input.readAllBytes());
                            scanned++;
                        }
                    }
                }
            }
        }
        assertTrue(scanned > 0, "production CodeSource contains no classes for " + location.className() + ": " + source);
    }

    private static ProductionLocation validatedProductionLocation(String className, URL codeSource) {
        validatedProductionPath(className, codeSource);
        return new ProductionLocation(className, codeSource);
    }

    private static Path validatedProductionPath(String className, URL codeSource) {
        Objects.requireNonNull(className, "className");
        if (codeSource == null) {
            throw new AssertionError("Production CodeSource URL is missing for " + className);
        }
        Path source = pathOf(codeSource);
        if (!Files.exists(source)) {
            throw new AssertionError("Production CodeSource does not exist for " + className + ": " + source);
        }
        String marker = markerResource(className);
        if (Files.isDirectory(source)) {
            if (!supportedProductionDirectory(className, source)) {
                throw new AssertionError("Unsupported or test-output CodeSource directory for " + className + ": " + source);
            }
            if (!Files.isRegularFile(source.resolve(marker))) {
                throw new AssertionError("Production CodeSource directory is missing marker " + marker + ": " + source);
            }
            return source;
        }
        if (!Files.isRegularFile(source)
                || !source.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            throw new AssertionError("Production CodeSource must be a supported directory or JAR for " + className + ": " + source);
        }
        try (JarFile archive = new JarFile(source.toFile())) {
            if (archive.getJarEntry(marker) == null) {
                throw new AssertionError("Production CodeSource JAR is missing marker " + marker + ": " + source);
            }
        } catch (IOException exception) {
            throw new AssertionError("Unable to validate production CodeSource JAR for " + className + ": " + source, exception);
        }
        return source;
    }

    private static boolean supportedProductionDirectory(String className, Path source) {
        String normalized = source.toAbsolutePath().normalize().toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        return normalized.endsWith("/" + productionProject(className) + "/build/classes/java/main");
    }

    private static Path productionDirectory(Path root, String className) {
        return root.resolve(productionProject(className)).resolve("build/classes/java/main");
    }

    private static String productionProject(String className) {
        String namespace = inspectedNamespace(className);
        if (API_NAMESPACE.equals(namespace)) {
            return "blendlib-api";
        }
        if (CORE_NAMESPACE.equals(namespace)) {
            return "blendlib-core";
        }
        if (COMMON_NAMESPACE.equals(namespace)) {
            return "blendlib-fabric-common";
        }
        if (SHOWCASE_NAMESPACE.equals(namespace)) {
            return "blendlib-showcase";
        }
        throw new AssertionError("No production project is defined for " + className);
    }

    private static String markerResource(String className) {
        return className.replace('.', '/') + ".class";
    }

    private static void writeClass(Path root, String resourceName, byte[] bytes) throws IOException {
        Path target = root.resolve(resourceName);
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
    }

    private static byte[] markerBytes(String className) throws IOException {
        ProductionLocation source = productionLocation(className);
        Path path = validatedProductionPath(className, source.codeSource());
        String marker = markerResource(className);
        if (Files.isDirectory(path)) {
            return Files.readAllBytes(path.resolve(marker));
        }
        try (JarFile archive = new JarFile(path.toFile()); var input = archive.getInputStream(archive.getJarEntry(marker))) {
            return input.readAllBytes();
        }
    }

    private static void writeMarkerArchive(Path archive, String className) throws IOException {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new JarEntry(markerResource(className)));
            output.write(markerBytes(className));
            output.closeEntry();
        }
    }

    private static void assertLoadsFromTheValidatedLocation(ProductionLocation location) throws IOException, ClassNotFoundException {
        try (ClientBlockingLoader loader = new ClientBlockingLoader(List.of(location))) {
            assertSame(loader, loader.loadClass(location.className()).getClassLoader());
        }
    }

    private static void assertNoClientReference(ProductionLocation location, String className, byte[] bytes) {
        String byteText = new String(bytes, StandardCharsets.ISO_8859_1);
        for (String forbidden : new String[] {
                CLIENT_HOST_INTERNAL,
                MINECRAFT_CLIENT_INTERNAL,
                BLAZE3D_INTERNAL,
                FABRIC_CLIENT_INTERNAL,
                "com.liy.blendlib.fabric.client.",
                "net.minecraft.client.",
                "com.mojang.blaze3d."}) {
            assertFalse(byteText.contains(forbidden),
                    () -> location.className() + " production bytecode " + className + " references " + forbidden);
        }
    }

    private static Path pathOf(URL source) {
        try {
            URI uri = source.toURI();
            if (!"file".equalsIgnoreCase(uri.getScheme()) || uri.isOpaque() || uri.getPath() == null || uri.getPath().isBlank()) {
                throw new AssertionError("Production CodeSource URL must be a non-empty file URL: " + source);
            }
            return Path.of(uri).toAbsolutePath().normalize();
        } catch (URISyntaxException exception) {
            throw new AssertionError("Invalid production CodeSource URL " + source, exception);
        }
    }

    private record ProductionLocation(String className, URL codeSource) { }

    /** Loads inspected server-owned BlendLib classes only from their validated module CodeSources. */
    private static final class ClientBlockingLoader extends URLClassLoader {
        private final Map<String, ProductionLocation> locationsByNamespace;

        private ClientBlockingLoader(List<ProductionLocation> locations) {
            super(new URL[0],
                    X4ShowcaseServerClassloadBoundaryTest.class.getClassLoader());
            this.locationsByNamespace = validatedLocations(locations);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (isClientNamespace(name)) {
                throw new ClassNotFoundException("Client namespace is intentionally unavailable to server boundary loader: " + name);
            }
            String namespace = inspectedNamespace(name);
            if (namespace != null) {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        ProductionLocation location = locationsByNamespace.get(namespace);
                        if (location == null) {
                            throw new ClassNotFoundException(
                                    "Inspected server namespace has no validated production CodeSource: " + name);
                        }
                        loaded = findClassFromValidatedLocation(name, location);
                    }
                    if (resolve) {
                        resolveClass(loaded);
                    }
                    return loaded;
                }
            }
            return super.loadClass(name, resolve);
        }

        private static boolean isClientNamespace(String name) {
            return name.startsWith("com.liy.blendlib.fabric.client.")
                    || name.startsWith("net.minecraft.client.")
                    || name.startsWith("net.fabricmc.fabric.api.client.")
                    || name.equals("net.fabricmc.api.ClientModInitializer")
                    || name.startsWith("com.mojang.blaze3d.");
        }

        private static Map<String, ProductionLocation> validatedLocations(List<ProductionLocation> locations) {
            if (locations == null || locations.isEmpty()) {
                throw new IllegalArgumentException("ClientBlockingLoader requires at least one validated production location");
            }
            Map<String, ProductionLocation> byNamespace = new HashMap<>();
            for (ProductionLocation location : locations) {
                Objects.requireNonNull(location, "location");
                validatedProductionPath(location.className(), location.codeSource());
                String namespace = inspectedNamespace(location.className());
                if (namespace == null) {
                    throw new AssertionError("Production location is not an inspected server namespace: " + location.className());
                }
                if (byNamespace.putIfAbsent(namespace, location) != null) {
                    throw new AssertionError("Duplicate validated production location for " + namespace);
                }
            }
            return Map.copyOf(byNamespace);
        }

        private Class<?> findClassFromValidatedLocation(String className, ProductionLocation location)
                throws ClassNotFoundException {
            String resourceName = markerResource(className);
            Path source = validatedProductionPath(location.className(), location.codeSource());
            byte[] bytes;
            try {
                if (Files.isDirectory(source)) {
                    Path classFile = source.resolve(resourceName);
                    if (!Files.isRegularFile(classFile)) {
                        throw new ClassNotFoundException(
                                "Inspected class is absent from validated production directory: " + className);
                    }
                    bytes = Files.readAllBytes(classFile);
                } else {
                    try (JarFile archive = new JarFile(source.toFile())) {
                        JarEntry entry = archive.getJarEntry(resourceName);
                        if (entry == null) {
                            throw new ClassNotFoundException(
                                    "Inspected class is absent from validated production JAR: " + className);
                        }
                        try (var input = archive.getInputStream(entry)) {
                            bytes = input.readAllBytes();
                        }
                    }
                }
            } catch (IOException exception) {
                throw new ClassNotFoundException(
                        "Unable to read inspected class from validated production CodeSource: " + className,
                        exception);
            }
            ProtectionDomain domain = new ProtectionDomain(
                    new CodeSource(location.codeSource(), (java.security.cert.Certificate[]) null), null);
            return defineClass(className, bytes, 0, bytes.length, domain);
        }
    }

    private static String inspectedNamespace(String className) {
        for (String namespace : List.of(API_NAMESPACE, CORE_NAMESPACE, COMMON_NAMESPACE, SHOWCASE_NAMESPACE)) {
            if (className.startsWith(namespace)) {
                return namespace;
            }
        }
        return null;
    }
}
