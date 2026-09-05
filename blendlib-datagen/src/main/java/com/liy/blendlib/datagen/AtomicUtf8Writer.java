package com.liy.blendlib.datagen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/**
 * Per-file UTF-8/LF writer that rejects non-atomic output publication.
 *
 * <p><strong>Stable boundary:</strong> a filesystem lacking atomic move support is a fail-closed
 * generation error rather than a quiet replace fallback. This prevents the generator from claiming
 * an atomic artifact when it could leave a partially published file.</p>
 */
final class AtomicUtf8Writer {
    private AtomicUtf8Writer() {
    }

    static void write(Path outputRoot, Path target, String lfText) {
        Path checkedRoot = Objects.requireNonNull(outputRoot, "outputRoot").toAbsolutePath().normalize();
        Path checkedTarget = Objects.requireNonNull(target, "target").toAbsolutePath().normalize();
        if (!checkedTarget.startsWith(checkedRoot)) {
            throw new DatagenException(DatagenDiagnosticCode.PATH_TRAVERSAL,
                    "Generated output escapes the approved root: " + checkedTarget);
        }
        if (!Objects.requireNonNull(lfText, "lfText").endsWith("\n") || lfText.indexOf('\r') >= 0) {
            throw new DatagenException(DatagenDiagnosticCode.INVALID_SPECIFICATION,
                    "Generated text must use LF and end with one LF");
        }
        Path temporary = null;
        try {
            Files.createDirectories(checkedTarget.getParent());
            temporary = Files.createTempFile(checkedTarget.getParent(), ".blendlib-", ".tmp");
            Files.writeString(temporary, lfText, StandardCharsets.UTF_8);
            Files.move(temporary, checkedTarget, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            deleteTemporary(temporary);
            throw new DatagenException(DatagenDiagnosticCode.ATOMIC_WRITE_FAILURE,
                    "Filesystem does not support atomic move for " + checkedTarget, exception);
        } catch (IOException exception) {
            deleteTemporary(temporary);
            throw new DatagenException(DatagenDiagnosticCode.OUTPUT_IO_FAILURE,
                    "Unable to write generated output " + checkedTarget, exception);
        }
    }

    private static void deleteTemporary(Path temporary) {
        if (temporary == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
            // The primary output error remains authoritative; the temporary path has no published contract.
        }
    }
}
