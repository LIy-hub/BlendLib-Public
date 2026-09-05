package com.liy.blendlib.showcase.perf.x7;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Offline, fail-closed verification of a complete retained X7 evidence directory.
 *
 * <p>Ordinary path traversal is useful only for structural retention checks: Java cannot make a
 * Windows parent-junction precheck plus later pathname open race-free. A result from that route
 * is therefore explicitly {@link ArtifactVerification.State#STRUCTURALLY_VALID_WAITING}, never
 * a trusted hardware input. {@link SecureDirectoryStream} relative traversal is the only
 * standard-Java route that can yield {@code TRUSTED}; unsupported providers, reparse points,
 * mutations, and every test seam remain non-trusted or fail closed.</p>
 */
public final class X7ArtifactVerifier {
    static final int MAX_DIRECTORY_DEPTH = 16;
    static final int MAX_INVENTORY_ENTRIES = 128;
    static final int MAX_RELATIVE_PATH_CHARS = 512;
    static final long MAX_TOTAL_ARTIFACT_BYTES = 256L * 1024L * 1024L;
    static final long MAX_MANIFEST_BYTES = X7BenchmarkEvidenceCodec.MAX_INPUT_BYTES;
    static final long MAX_RAW_SAMPLES_BYTES = X7BenchmarkEvidenceCodec.MAX_INPUT_BYTES;
    static final long MAX_P7_REPORT_BYTES = X7BenchmarkEvidenceCodec.MAX_INPUT_BYTES;
    static final long MAX_ENVIRONMENT_REPORT_BYTES = X7BenchmarkEvidenceCodec.MAX_INPUT_BYTES;
    static final long MAX_CAPABILITY_REPORT_BYTES = X7BenchmarkEvidenceCodec.MAX_INPUT_BYTES;
    static final long MAX_OWNER_RECEIPT_BYTES = X7BenchmarkEvidenceCodec.MAX_INPUT_BYTES;
    static final long MAX_CAPTURE_LOG_BYTES = 16L * 1024L * 1024L;
    static final long MAX_SCREENSHOT_BYTES = 32L * 1024L * 1024L;
    static final long MAX_JFR_BYTES = 128L * 1024L * 1024L;
    private static final long MAX_UNLISTED_FILE_BYTES = 16L * 1024L * 1024L;
    private static final int WINDOWS_FILE_ATTRIBUTE_REPARSE_POINT = 0x400;
    private static final int COPY_BUFFER_BYTES = 8_192;

    private X7ArtifactVerifier() {
    }

    /** Verifies one production filesystem root and returns an opaque, evidence-bound result. */
    public static ArtifactVerification verify(X7BenchmarkEvidence evidence, Path artifactRoot) {
        return verifyInternal(evidence, artifactRoot, TestFault.NONE, false);
    }

    /** Deterministic no-host-privilege seam for a selected reparse-point observation. */
    static ArtifactVerification verifyWithForcedReparseForTest(
            X7BenchmarkEvidence evidence, Path artifactRoot, Path forcedReparsePath) {
        return verifyInternal(evidence, artifactRoot,
                new TestFault(Objects.requireNonNull(forcedReparsePath, "forcedReparsePath"), null, null, null, false), true);
    }

    /**
     * Deterministic same-entity seam used when the local filesystem cannot create a hard link.
     * Every result from this seam is permanently test-only and cannot be used as trusted input.
     */
    static ArtifactVerification verifyWithForcedSameFileForTest(
            X7BenchmarkEvidence evidence, Path artifactRoot, Path first, Path second) {
        return verifyInternal(evidence, artifactRoot,
                new TestFault(null, Objects.requireNonNull(first, "first"), Objects.requireNonNull(second, "second"), null, false),
                true);
    }

    /** Deterministic injected ancestor-swap/race signal; the verifier must reject rather than trust it. */
    static ArtifactVerification verifyWithForcedPathRaceForTest(
            X7BenchmarkEvidence evidence, Path artifactRoot, Path racedPath) {
        return verifyInternal(evidence, artifactRoot,
                new TestFault(null, null, null, Objects.requireNonNull(racedPath, "racedPath"), false), true);
    }

    /** Deterministic seam proving that an ordinary provider cannot create a trusted traversal. */
    static ArtifactVerification verifyWithForcedInsecureProviderForTest(
            X7BenchmarkEvidence evidence, Path artifactRoot) {
        return verifyInternal(evidence, artifactRoot,
                new TestFault(null, null, null, null, true), true);
    }

    private static ArtifactVerification verifyInternal(
            X7BenchmarkEvidence evidence, Path artifactRoot, TestFault fault, boolean testOnly) {
        X7BenchmarkEvidence checked = Objects.requireNonNull(evidence, "evidence");
        Path suppliedRoot = Objects.requireNonNull(artifactRoot, "artifactRoot");
        List<String> failures = new ArrayList<>();
        Root root = establishRoot(suppliedRoot, fault, failures);
        if (root == null) {
            return ArtifactVerification.failed(failures);
        }

        byte[] expectedManifestBytes;
        String evidenceDigest;
        try {
            expectedManifestBytes = X7BenchmarkEvidenceCodec.canonicalJson(checked).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            evidenceDigest = sha256(expectedManifestBytes);
        } catch (RuntimeException exception) {
            failures.add("evidence cannot produce bounded canonical manifest bytes: " + exception.getMessage());
            return ArtifactVerification.failed(failures);
        }
        if (expectedManifestBytes.length > MAX_MANIFEST_BYTES) {
            failures.add("canonical manifest exceeds the manifest byte budget");
            return ArtifactVerification.failed(failures);
        }

        Map<String, ArtifactRule> expected = expectedInventory(checked, expectedManifestBytes.length, failures);
        if (!failures.isEmpty()) {
            return ArtifactVerification.failed(failures);
        }

        ScanResult scan;
        try {
            scan = scanInventory(root, expected, fault, failures);
        } catch (RuntimeException exception) {
            failures.add("artifact inventory provider failed closed: " + exception.getClass().getSimpleName());
            return ArtifactVerification.failed(failures);
        }
        if (scan == null) {
            return ArtifactVerification.failed(failures);
        }
        verifyInventory(checked, expectedManifestBytes, expected, scan.entriesByRelativePath(), failures);
        verifyDistinctFilesystemEntities(scan.entries(), fault, failures);
        if (!failures.isEmpty()) {
            return ArtifactVerification.failed(failures);
        }

        ArtifactVerification.State state = scan.trustedTraversal()
                ? ArtifactVerification.State.TRUSTED
                : ArtifactVerification.State.STRUCTURALLY_VALID_WAITING;
        FileEntry manifest = scan.entriesByRelativePath().get(checked.artifactManifest().manifestPath());
        return ArtifactVerification.successful(state, new EvidenceBinding(
                evidenceDigest,
                root.realPath().toString(),
                manifest.snapshot().sha256(),
                manifest.snapshot().byteCount(),
                inventoryDigest(scan.entriesByRelativePath()),
                state,
                testOnly));
    }

    private static Root establishRoot(Path suppliedRoot, TestFault fault, List<String> failures) {
        try {
            Path absolute = suppliedRoot.toAbsolutePath().normalize();
            if (absolute.toString().length() > MAX_RELATIVE_PATH_CHARS * 4) {
                failures.add("artifact root path exceeds the bounded path length");
                return null;
            }
            BasicFileAttributes attributes = attributesNoFollow(absolute);
            if (rejectLinkOrReparse(absolute, attributes, fault, failures, "artifact root")) {
                return null;
            }
            if (!attributes.isDirectory()) {
                failures.add("artifact root is not a directory");
                return null;
            }
            Path real = absolute.toRealPath();
            if (!Files.isSameFile(absolute, real)) {
                failures.add("artifact root changed while its real identity was established");
                return null;
            }
            return new Root(absolute, real, attributes);
        } catch (IOException | RuntimeException exception) {
            failures.add("artifact root could not establish a real identity: " + exception.getClass().getSimpleName());
            return null;
        }
    }

    private static Map<String, ArtifactRule> expectedInventory(
            X7BenchmarkEvidence evidence, int manifestBytes, List<String> failures) {
        Map<String, ArtifactRule> expected = new HashMap<>();
        Set<String> foldedPaths = new HashSet<>();
        Set<String> foldedKeys = new HashSet<>();
        String manifestPath = evidence.artifactManifest().manifestPath();
        addExpected(expected, foldedPaths, manifestPath, new ArtifactRule(null, MAX_MANIFEST_BYTES, manifestBytes), failures,
                "manifest");
        for (X7BenchmarkEvidence.Artifact artifact : evidence.artifactManifest().entries()) {
            if (!foldedKeys.add(fold(artifact.key()))) {
                failures.add("duplicate artifact key: " + artifact.key());
                continue;
            }
            if (artifact.path().equals(manifestPath)) {
                failures.add("artifact manifest must exclude itself from its entries");
                continue;
            }
            long cap = maxArtifactBytes(artifact.kind());
            if (artifact.byteCount() > cap) {
                failures.add("artifact " + artifact.key() + " exceeds its " + artifact.kind() + " byte budget");
            }
            addExpected(expected, foldedPaths, artifact.path(), new ArtifactRule(artifact, cap, artifact.byteCount()), failures,
                    "artifact " + artifact.key());
        }
        return expected;
    }

    private static void addExpected(
            Map<String, ArtifactRule> expected,
            Set<String> foldedPaths,
            String path,
            ArtifactRule rule,
            List<String> failures,
            String description) {
        if (!X7BenchmarkEvidenceValidator.isSafeRelativePath(path) || path.length() > MAX_RELATIVE_PATH_CHARS) {
            failures.add(description + " path is not a bounded canonical relative path");
            return;
        }
        if (!foldedPaths.add(fold(path)) || expected.putIfAbsent(path, rule) != null) {
            failures.add("duplicate or case-alias artifact path: " + path);
        }
    }

    private static ScanResult scanInventory(
            Root root, Map<String, ArtifactRule> expected, TestFault fault, List<String> failures) {
        if (!fault.isActive() && !isWindows() && root.attributes().fileKey() != null) {
            try (DirectoryStream<Path> opened = Files.newDirectoryStream(root.realPath())) {
                if (opened instanceof SecureDirectoryStream<?> secureDirectory) {
                    @SuppressWarnings("unchecked")
                    SecureDirectoryStream<Path> secure = (SecureDirectoryStream<Path>) secureDirectory;
                    Map<String, FileEntry> entries = new HashMap<>();
                    InventoryBudget budget = new InventoryBudget();
                    scanSecureDirectory(secure, root, Path.of(""), 0, expected, entries, budget, failures);
                    BasicFileAttributes after = attributesNoFollow(root.absolutePath());
                    if (!sameStableIdentity(root.attributes(), after)) {
                        failures.add("artifact root changed during secure traversal");
                    }
                    return new ScanResult(entries, true);
                }
            } catch (IOException exception) {
                failures.add("secure artifact-root traversal failed: " + exception.getClass().getSimpleName());
                return null;
            }
        }
        Map<String, FileEntry> entries = new HashMap<>();
        InventoryBudget budget = new InventoryBudget();
        scanStructuralInventory(root, expected, fault, entries, budget, failures);
        return new ScanResult(entries, false);
    }

    private static void scanSecureDirectory(
            SecureDirectoryStream<Path> directory,
            Root root,
            Path relativeDirectory,
            int depth,
            Map<String, ArtifactRule> expected,
            Map<String, FileEntry> entries,
            InventoryBudget budget,
            List<String> failures) {
        if (depth > MAX_DIRECTORY_DEPTH) {
            failures.add("artifact inventory exceeds the maximum directory depth");
            return;
        }
        List<Path> names = new ArrayList<>();
        try {
            for (Path entry : directory) {
                if (!budget.discover(failures)) {
                    return;
                }
                Path name = entry.getFileName();
                if (name == null || name.getNameCount() != 1 || name.toString().isEmpty()) {
                    failures.add("secure inventory returned an invalid entry name");
                    return;
                }
                names.add(name);
            }
        } catch (RuntimeException exception) {
            failures.add("secure inventory enumeration failed: " + exception.getClass().getSimpleName());
            return;
        }
        names.sort(Comparator.comparing(Path::toString));
        for (Path name : names) {
            Path relative = relativeDirectory.resolve(name).normalize();
            String relativeText = canonicalRelative(relative, failures, "secure inventory entry");
            if (relativeText == null) {
                continue;
            }
            try {
                BasicFileAttributes before = secureAttributes(directory, name);
                if (before.isSymbolicLink() || before.isOther()) {
                    failures.add("secure inventory entry contains a link or special filesystem entry");
                    continue;
                }
                if (before.isDirectory()) {
                    if (depth >= MAX_DIRECTORY_DEPTH) {
                        failures.add("artifact inventory exceeds the maximum directory depth");
                        continue;
                    }
                    try (SecureDirectoryStream<Path> child = directory.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) {
                        scanSecureDirectory(child, root, relative, depth + 1, expected, entries, budget, failures);
                    }
                } else if (before.isRegularFile()) {
                    ArtifactRule rule = expected.get(relativeText);
                    FileSnapshot snapshot = snapshotSecure(directory, name, before,
                            rule == null ? MAX_UNLISTED_FILE_BYTES : rule.maxBytes(),
                            relativeText.equals(rootManifestPath(expected)), budget, failures);
                    if (snapshot != null && entries.putIfAbsent(relativeText,
                            new FileEntry(relativeText, root.realPath().resolve(relative), snapshot)) != null) {
                        failures.add("duplicate inventory path: " + relativeText);
                    }
                } else {
                    failures.add("inventory contains an ineligible filesystem entry");
                }
            } catch (IOException | RuntimeException exception) {
                failures.add("secure inventory entry could not be inspected: " + exception.getClass().getSimpleName());
            }
        }
    }

    private static void scanStructuralInventory(
            Root root,
            Map<String, ArtifactRule> expected,
            TestFault fault,
            Map<String, FileEntry> entries,
            InventoryBudget budget,
            List<String> failures) {
        Deque<DirectoryNode> directories = new ArrayDeque<>();
        directories.addLast(new DirectoryNode(root.absolutePath(), Path.of(""), 0));
        while (!directories.isEmpty()) {
            DirectoryNode node = directories.removeFirst();
            if (node.depth() > MAX_DIRECTORY_DEPTH) {
                failures.add("artifact inventory exceeds the maximum directory depth");
                continue;
            }
            for (Path child : listChildren(node.path(), budget, failures)) {
                Path name = child.getFileName();
                Path relative = node.relativePath().resolve(name).normalize();
                String relativeText = canonicalRelative(relative, failures, "inventory entry");
                if (relativeText == null) {
                    continue;
                }
                try {
                    BasicFileAttributes before = attributesNoFollow(child);
                    if (rejectLinkOrReparse(child, before, fault, failures, "inventory entry")) {
                        continue;
                    }
                    if (fault.isRaced(child)) {
                        failures.add("inventory entry changed during an injected ancestor replacement race");
                        continue;
                    }
                    Path real = child.toRealPath();
                    if (!real.startsWith(root.realPath())) {
                        failures.add("inventory entry resolves outside the real artifact root");
                        continue;
                    }
                    if (before.isDirectory()) {
                        if (node.depth() >= MAX_DIRECTORY_DEPTH) {
                            failures.add("artifact inventory exceeds the maximum directory depth");
                        } else {
                            directories.addLast(new DirectoryNode(child, relative, node.depth() + 1));
                        }
                    } else if (before.isRegularFile()) {
                        ArtifactRule rule = expected.get(relativeText);
                        FileSnapshot snapshot = snapshotPath(child, before,
                                rule == null ? MAX_UNLISTED_FILE_BYTES : rule.maxBytes(),
                                relativeText.equals(rootManifestPath(expected)), budget, failures);
                        if (snapshot != null && entries.putIfAbsent(relativeText, new FileEntry(relativeText, child, snapshot)) != null) {
                            failures.add("duplicate inventory path: " + relativeText);
                        }
                    } else {
                        failures.add("inventory contains an ineligible filesystem entry");
                    }
                } catch (IOException | RuntimeException exception) {
                    failures.add("inventory entry could not be inspected: " + exception.getClass().getSimpleName());
                }
            }
        }
    }

    private static List<Path> listChildren(Path directory, InventoryBudget budget, List<String> failures) {
        List<Path> children = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path child : stream) {
                if (!budget.discover(failures)) {
                    return List.of();
                }
                children.add(child);
            }
        } catch (IOException exception) {
            failures.add("artifact inventory cannot list " + directory + ": " + exception.getClass().getSimpleName());
            return List.of();
        }
        children.sort(Comparator.comparing(Path::toString));
        return children;
    }

    private static String canonicalRelative(Path path, List<String> failures, String description) {
        if (path.isAbsolute() || path.getNameCount() == 0) {
            failures.add(description + " is not a non-empty relative path");
            return null;
        }
        String text = path.toString().replace('\\', '/');
        if (!X7BenchmarkEvidenceValidator.isSafeRelativePath(text) || text.length() > MAX_RELATIVE_PATH_CHARS) {
            failures.add(description + " exceeds the bounded canonical path contract");
            return null;
        }
        return text;
    }

    private static BasicFileAttributes secureAttributes(SecureDirectoryStream<Path> directory, Path name) throws IOException {
        BasicFileAttributeView view = directory.getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            throw new IOException("secure directory does not expose BasicFileAttributeView");
        }
        return view.readAttributes();
    }

    private static FileSnapshot snapshotSecure(
            SecureDirectoryStream<Path> directory,
            Path name,
            BasicFileAttributes before,
            long maximumBytes,
            boolean retainBytes,
            InventoryBudget budget,
            List<String> failures) throws IOException {
        if (!budget.beginFile(before.size(), maximumBytes, failures)) {
            return null;
        }
        MessageDigest digest = newDigest();
        ByteArrayOutputStream retained = retainBytes ? new ByteArrayOutputStream((int) Math.min(before.size(), MAX_MANIFEST_BYTES)) : null;
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        long bytes = 0L;
        try (SeekableByteChannel channel = directory.newByteChannel(name,
                Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
                InputStream input = Channels.newInputStream(channel)) {
            for (int read; (read = input.read(buffer)) >= 0;) {
                if (read > 0) {
                    if (bytes > maximumBytes - read || !budget.read(read, failures)) {
                        throw new IOException("artifact read exceeded a bounded budget");
                    }
                    bytes += read;
                    digest.update(buffer, 0, read);
                    if (retained != null) {
                        retained.write(buffer, 0, read);
                    }
                }
            }
        }
        BasicFileAttributes after = secureAttributes(directory, name);
        if (!sameStableIdentity(before, after)) {
            failures.add("secure inventory entry changed while it was read");
            return null;
        }
        return new FileSnapshot(bytes, HexFormat.of().formatHex(digest.digest()), retained == null ? null : retained.toByteArray());
    }

    private static FileSnapshot snapshotPath(
            Path file,
            BasicFileAttributes before,
            long maximumBytes,
            boolean retainBytes,
            InventoryBudget budget,
            List<String> failures) throws IOException {
        if (!budget.beginFile(before.size(), maximumBytes, failures)) {
            return null;
        }
        MessageDigest digest = newDigest();
        ByteArrayOutputStream retained = retainBytes ? new ByteArrayOutputStream((int) Math.min(before.size(), MAX_MANIFEST_BYTES)) : null;
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        long bytes = 0L;
        try (InputStream input = openNoFollow(file)) {
            for (int read; (read = input.read(buffer)) >= 0;) {
                if (read > 0) {
                    if (bytes > maximumBytes - read || !budget.read(read, failures)) {
                        throw new IOException("artifact read exceeded a bounded budget");
                    }
                    bytes += read;
                    digest.update(buffer, 0, read);
                    if (retained != null) {
                        retained.write(buffer, 0, read);
                    }
                }
            }
        }
        BasicFileAttributes after = attributesNoFollow(file);
        if (!sameStructuralIdentity(before, after)) {
            failures.add("inventory entry changed while it was read");
            return null;
        }
        return new FileSnapshot(bytes, HexFormat.of().formatHex(digest.digest()), retained == null ? null : retained.toByteArray());
    }

    private static void verifyInventory(
            X7BenchmarkEvidence evidence,
            byte[] expectedManifestBytes,
            Map<String, ArtifactRule> expected,
            Map<String, FileEntry> actual,
            List<String> failures) {
        for (Map.Entry<String, ArtifactRule> item : expected.entrySet()) {
            String path = item.getKey();
            ArtifactRule rule = item.getValue();
            FileEntry entry = actual.get(path);
            if (entry == null) {
                failures.add("manifest inventory is missing " + path);
                continue;
            }
            if (rule.artifact() == null) {
                byte[] retained = entry.snapshot().retainedBytes();
                if (retained == null || !Arrays.equals(expectedManifestBytes, retained)) {
                    failures.add("manifest bytes do not exactly match the canonical evidence envelope");
                }
                continue;
            }
            X7BenchmarkEvidence.Artifact artifact = rule.artifact();
            if (entry.snapshot().byteCount() != artifact.byteCount()) {
                failures.add("artifact " + artifact.key() + " byte count mismatch");
            }
            if (!entry.snapshot().sha256().equals(artifact.sha256())) {
                failures.add("artifact " + artifact.key() + " SHA-256 mismatch");
            }
        }
        for (String path : actual.keySet()) {
            if (!expected.containsKey(path)) {
                failures.add("unlisted artifact file: " + path);
            }
        }
        String manifestPath = evidence.artifactManifest().manifestPath();
        if (!actual.containsKey(manifestPath)) {
            failures.add("manifest is missing from the exact root inventory");
        }
    }

    /** Checks every bounded inventory pair, including the self-excluded manifest, by actual entity identity. */
    private static void verifyDistinctFilesystemEntities(
            List<FileEntry> entries, TestFault fault, List<String> failures) {
        for (int left = 0; left < entries.size(); left++) {
            FileEntry first = entries.get(left);
            for (int right = left + 1; right < entries.size(); right++) {
                FileEntry second = entries.get(right);
                try {
                    boolean same = fault.forcesSameFile(first.path(), second.path()) || Files.isSameFile(first.path(), second.path());
                    if (same) {
                        failures.add("duplicate filesystem entity: " + first.relativePath() + " and " + second.relativePath());
                    }
                } catch (IOException | RuntimeException exception) {
                    failures.add("cannot prove distinct filesystem entities: " + exception.getClass().getSimpleName());
                }
            }
        }
    }

    static long maxArtifactBytes(X7BenchmarkEvidence.ArtifactKind kind) {
        return switch (Objects.requireNonNull(kind, "kind")) {
            case JFR_ALLOCATION -> MAX_JFR_BYTES;
            case P7_CAPTURE_REPORT -> MAX_P7_REPORT_BYTES;
            case P7_RAW_SAMPLES -> MAX_RAW_SAMPLES_BYTES;
            case CAPTURE_LOG -> MAX_CAPTURE_LOG_BYTES;
            case SCREENSHOT -> MAX_SCREENSHOT_BYTES;
            case ENVIRONMENT_REPORT -> MAX_ENVIRONMENT_REPORT_BYTES;
            case CAPABILITY_REPORT -> MAX_CAPABILITY_REPORT_BYTES;
            case OWNER_RECEIPT -> MAX_OWNER_RECEIPT_BYTES;
        };
    }

    /** Reads a bounded retained text/JSON artifact only after no-follow path checks. */
    static byte[] readRetainedArtifact(
            Path artifactRoot, String declaredPath, X7BenchmarkEvidence.ArtifactKind kind) throws IOException {
        return readRetained(artifactRoot, declaredPath, kind, (int) maxArtifactBytes(kind));
    }

    /** Streams only a bounded prefix for binary evidence such as JFR and PNG. */
    static byte[] readRetainedPrefix(
            Path artifactRoot, String declaredPath, X7BenchmarkEvidence.ArtifactKind kind, int prefixBytes) throws IOException {
        if (prefixBytes <= 0 || prefixBytes > maxArtifactBytes(kind)) {
            throw new IOException("retained artifact prefix limit is invalid");
        }
        List<String> failures = new ArrayList<>();
        Root root = establishRoot(Objects.requireNonNull(artifactRoot, "artifactRoot"), TestFault.NONE, failures);
        if (root == null) {
            throw new IOException(String.join("; ", failures));
        }
        Path file = resolveContainedFile(root, Objects.requireNonNull(declaredPath, "declaredPath"), failures);
        if (file == null) {
            throw new IOException(String.join("; ", failures));
        }
        if (Files.size(file) > maxArtifactBytes(kind)) {
            throw new IOException("retained artifact exceeds its byte budget");
        }
        return readPrefix(file, prefixBytes);
    }

    private static byte[] readRetained(
            Path artifactRoot, String declaredPath, X7BenchmarkEvidence.ArtifactKind kind, int maximumBytes) throws IOException {
        List<String> failures = new ArrayList<>();
        Root root = establishRoot(Objects.requireNonNull(artifactRoot, "artifactRoot"), TestFault.NONE, failures);
        if (root == null) {
            throw new IOException(String.join("; ", failures));
        }
        Path file = resolveContainedFile(root, Objects.requireNonNull(declaredPath, "declaredPath"), failures);
        if (file == null) {
            throw new IOException(String.join("; ", failures));
        }
        long cap = Math.min(maxArtifactBytes(kind), maximumBytes);
        return readBounded(file, cap);
    }

    private static Path resolveContainedFile(Root root, String declaredPath, List<String> failures) {
        if (!X7BenchmarkEvidenceValidator.isSafeRelativePath(declaredPath)
                || declaredPath.length() > MAX_RELATIVE_PATH_CHARS) {
            failures.add("retained artifact path is not bounded and canonical");
            return null;
        }
        Path current = root.absolutePath();
        for (Path segment : Path.of(declaredPath)) {
            current = current.resolve(segment);
            try {
                BasicFileAttributes attributes = attributesNoFollow(current);
                if (rejectLinkOrReparse(current, attributes, TestFault.NONE, failures, "retained artifact")) {
                    return null;
                }
                if (!current.equals(root.absolutePath()) && !attributes.isDirectory() && !attributes.isRegularFile()) {
                    failures.add("retained artifact contains an ineligible filesystem entry");
                    return null;
                }
                Path real = current.toRealPath();
                if (!real.startsWith(root.realPath())) {
                    failures.add("retained artifact resolves outside the real root");
                    return null;
                }
            } catch (IOException exception) {
                failures.add("retained artifact is missing or unreadable: " + exception.getClass().getSimpleName());
                return null;
            }
        }
        try {
            if (!attributesNoFollow(current).isRegularFile()) {
                failures.add("retained artifact is not a regular file");
                return null;
            }
        } catch (IOException exception) {
            failures.add("retained artifact cannot be rechecked");
            return null;
        }
        return current;
    }

    private static byte[] readBounded(Path file, long maximumBytes) throws IOException {
        if (Files.size(file) > maximumBytes) {
            throw new IOException("retained artifact exceeds its byte budget");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(Files.size(file), maximumBytes));
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        long bytes = 0L;
        try (InputStream input = openNoFollow(file)) {
            for (int read; (read = input.read(buffer)) >= 0;) {
                if (read > 0) {
                    if (bytes > maximumBytes - read) {
                        throw new IOException("retained artifact exceeds its byte budget");
                    }
                    bytes += read;
                    output.write(buffer, 0, read);
                }
            }
        }
        return output.toByteArray();
    }

    private static byte[] readPrefix(Path file, int prefixBytes) throws IOException {
        byte[] output = new byte[prefixBytes];
        int offset = 0;
        try (InputStream input = openNoFollow(file)) {
            while (offset < output.length) {
                int read = input.read(output, offset, output.length - offset);
                if (read < 0) {
                    break;
                }
                if (read > 0) {
                    offset += read;
                }
            }
        }
        return Arrays.copyOf(output, offset);
    }

    /** Computes a lowercase SHA-256 digest with a streaming no-follow read. */
    public static String sha256(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        MessageDigest digest = newDigest();
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        try (InputStream input = openNoFollow(file)) {
            for (int read; (read = input.read(buffer)) >= 0;) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(newDigest().digest(Objects.requireNonNull(bytes, "bytes")));
    }

    private static InputStream openNoFollow(Path path) throws IOException {
        Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        return Channels.newInputStream(Files.newByteChannel(path, options));
    }

    private static BasicFileAttributes attributesNoFollow(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean rejectLinkOrReparse(
            Path path, BasicFileAttributes attributes, TestFault fault, List<String> failures, String description) throws IOException {
        if (fault.isForcedReparse(path)) {
            failures.add(description + " contains a Windows reparse point or other special link");
            return true;
        }
        if (Files.isSymbolicLink(path) || attributes.isSymbolicLink()) {
            failures.add(description + " contains a symbolic link");
            return true;
        }
        if (isWindows()) {
            try {
                Object raw = Files.getAttribute(path, "dos:attributes", LinkOption.NOFOLLOW_LINKS);
                if (!(raw instanceof Number number)) {
                    failures.add(description + " cannot prove the Windows reparse-point state");
                    return true;
                }
                if ((number.intValue() & WINDOWS_FILE_ATTRIBUTE_REPARSE_POINT) != 0) {
                    failures.add(description + " contains a Windows reparse point or other special link");
                    return true;
                }
            } catch (UnsupportedOperationException | IllegalArgumentException exception) {
                failures.add(description + " cannot prove the Windows reparse-point state");
                return true;
            }
        } else if (attributes.isOther()) {
            failures.add(description + " contains an ineligible special filesystem entry");
            return true;
        }
        return false;
    }

    private static boolean sameStableIdentity(BasicFileAttributes before, BasicFileAttributes after) {
        Object beforeKey = before.fileKey();
        Object afterKey = after.fileKey();
        return beforeKey != null
                && afterKey != null
                && beforeKey.equals(afterKey)
                && before.size() == after.size()
                && before.lastModifiedTime().equals(after.lastModifiedTime());
    }

    /** A pathname-only check is deliberately weaker and never promotes traversal to TRUSTED. */
    private static boolean sameStructuralIdentity(BasicFileAttributes before, BasicFileAttributes after) {
        Object beforeKey = before.fileKey();
        Object afterKey = after.fileKey();
        if (beforeKey != null && afterKey != null && !beforeKey.equals(afterKey)) {
            return false;
        }
        return before.size() == after.size() && before.lastModifiedTime().equals(after.lastModifiedTime());
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", exception);
        }
    }

    private static String inventoryDigest(Map<String, FileEntry> inventory) {
        MessageDigest digest = newDigest();
        inventory.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            FileSnapshot file = entry.getValue().snapshot();
            digest.update(entry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Long.toString(file.byteCount()).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            digest.update((byte) 0);
            digest.update(file.sha256().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            digest.update((byte) '\n');
        });
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String rootManifestPath(Map<String, ArtifactRule> expected) {
        return expected.entrySet().stream()
                .filter(entry -> entry.getValue().artifact() == null)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow();
    }

    private static String fold(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private record Root(Path absolutePath, Path realPath, BasicFileAttributes attributes) {
    }

    private record ArtifactRule(X7BenchmarkEvidence.Artifact artifact, long maxBytes, long expectedBytes) {
    }

    private record DirectoryNode(Path path, Path relativePath, int depth) {
    }

    private record FileEntry(String relativePath, Path path, FileSnapshot snapshot) {
    }

    private record FileSnapshot(long byteCount, String sha256, byte[] retainedBytes) {
    }

    private record ScanResult(Map<String, FileEntry> entriesByRelativePath, boolean trustedTraversal) {
        private ScanResult {
            entriesByRelativePath = Map.copyOf(entriesByRelativePath);
        }

        private List<FileEntry> entries() {
            return entriesByRelativePath.values().stream().sorted(Comparator.comparing(FileEntry::relativePath)).toList();
        }
    }

    private record EvidenceBinding(
            String canonicalEvidenceDigest,
            String realRootIdentity,
            String manifestDigest,
            long manifestBytes,
            String inventoryDigest,
            ArtifactVerification.State state,
            boolean testOnly) {
    }

    private static final class InventoryBudget {
        private int entries;
        private long bytesRead;

        private boolean discover(List<String> failures) {
            if (++entries > MAX_INVENTORY_ENTRIES) {
                failures.add("artifact inventory exceeds the entry-count limit");
                return false;
            }
            return true;
        }

        private boolean beginFile(long declaredSize, long maximumBytes, List<String> failures) {
            if (declaredSize < 0L || declaredSize > maximumBytes) {
                failures.add("artifact exceeds its per-file byte budget");
                return false;
            }
            if (bytesRead > MAX_TOTAL_ARTIFACT_BYTES - declaredSize) {
                failures.add("artifact inventory exceeds the total-byte budget");
                return false;
            }
            return true;
        }

        private boolean read(int count, List<String> failures) {
            if (bytesRead > MAX_TOTAL_ARTIFACT_BYTES - count) {
                failures.add("artifact inventory exceeds the total-byte budget");
                return false;
            }
            bytesRead += count;
            return true;
        }
    }

    private record TestFault(
            Path forcedReparse,
            Path forcedSameFirst,
            Path forcedSameSecond,
            Path forcedRace,
            boolean forceInsecureProvider) {
        private static final TestFault NONE = new TestFault(null, null, null, null, false);

        private boolean isActive() {
            return forcedReparse != null || forcedSameFirst != null || forcedRace != null || forceInsecureProvider;
        }

        private boolean isForcedReparse(Path path) {
            return matches(forcedReparse, path);
        }

        private boolean isRaced(Path path) {
            return matches(forcedRace, path);
        }

        private boolean forcesSameFile(Path first, Path second) {
            return (matches(forcedSameFirst, first) && matches(forcedSameSecond, second))
                    || (matches(forcedSameFirst, second) && matches(forcedSameSecond, first));
        }

        private static boolean matches(Path expected, Path actual) {
            return expected != null && expected.toAbsolutePath().normalize().equals(actual.toAbsolutePath().normalize());
        }
    }

    /** Opaque verification result. Success construction is owned only by the verifier. */
    public static final class ArtifactVerification {
        private final State state;
        private final List<String> failures;
        private final EvidenceBinding binding;

        private ArtifactVerification(State state, List<String> failures, EvidenceBinding binding) {
            this.state = Objects.requireNonNull(state, "state");
            this.failures = List.copyOf(Objects.requireNonNull(failures, "failures"));
            this.binding = binding;
            if (state == State.FAILED && this.failures.isEmpty()) {
                throw new IllegalArgumentException("failed artifact evidence must retain a reason");
            }
            if (state != State.FAILED && (binding == null || !this.failures.isEmpty())) {
                throw new IllegalArgumentException("successful structural evidence requires an owner binding and no failures");
            }
        }

        private static ArtifactVerification successful(State state, EvidenceBinding binding) {
            return new ArtifactVerification(state, List.of(), binding);
        }

        private static ArtifactVerification failed(List<String> failures) {
            return new ArtifactVerification(State.FAILED,
                    failures.isEmpty() ? List.of("artifact verification failed without a retained reason") : failures, null);
        }

        public State state() {
            return state;
        }

        public List<String> failures() {
            return failures;
        }

        /** True only for a bounded structural inventory that has no observed failure. */
        public boolean structurallyValid() {
            return state != State.FAILED;
        }

        /** Compatibility spelling for structural verification; it is deliberately not a hardware-trust claim. */
        public boolean verified() {
            return structurallyValid();
        }

        /** Only a secure relative-handle traversal can return true. */
        public boolean hasTrustedTraversal() {
            return state == State.TRUSTED && binding != null && !binding.testOnly();
        }

        /** Re-verifies current root bytes before another owner may consume this structural token. */
        boolean attests(X7BenchmarkEvidence evidence, Path artifactRoot) {
            if (!structurallyValid() || binding == null || binding.testOnly()) {
                return false;
            }
            ArtifactVerification refreshed = X7ArtifactVerifier.verify(evidence, artifactRoot);
            return refreshed.state != State.FAILED && binding.equals(refreshed.binding);
        }

        /** Result states deliberately distinguish retention from race-safe trusted traversal. */
        public enum State {
            TRUSTED,
            STRUCTURALLY_VALID_WAITING,
            FAILED
        }
    }
}
