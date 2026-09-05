package com.liy.blendlib.datagen;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Immutable manifest of files atomically emitted by one data-generation invocation.
 *
 * <p><strong>Stable boundary:</strong> paths are output artifacts only; this value retains no open
 * stream, Minecraft resource manager, platform type, or runtime model handle.</p>
 *
 * @param files immutable absolute normalized output file paths
 */
public record DatagenOutput(List<Path> files) {
    /**
     * Validates an immutable output manifest.
     */
    public DatagenOutput {
        files = List.copyOf(Objects.requireNonNull(files, "files"));
        if (files.isEmpty() || files.stream().anyMatch(path -> path == null || !path.isAbsolute())) {
            throw new IllegalArgumentException("files must be non-empty absolute paths");
        }
    }
}
