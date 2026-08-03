package com.liy.blendlib.fabric.client.reload;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.diagnostic.BlendDiagnostic;
import com.liy.blendlib.core.diagnostic.DiagnosticSeverity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Emits bounded, structured reload diagnostics after a generation has been atomically published.
 *
 * <p>The reporter intentionally belongs to reload apply rather than render submit. It retains only the keys and
 * diagnostics emitted for the newest active generation, so repeated or late stale apply calls cannot produce an
 * unbounded log-side cache or repeat a missing model's primary diagnostic.</p>
 */
final class ReloadDiagnosticsReporter {
    private final Sink sink;
    private final MissingModelDiagnosticDeduplicator primaryDiagnosticDeduplicator =
            new MissingModelDiagnosticDeduplicator();
    private long detailedGeneration = -1L;
    private final Set<BlendDiagnostic> reportedGlobalDiagnostics = new HashSet<>();

    ReloadDiagnosticsReporter(Sink sink) {
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    static ReloadDiagnosticsReporter production() {
        return new ReloadDiagnosticsReporter(new SystemSink(System.getLogger("BlendLib")));
    }

    /**
     * Reports every completed apply with a production summary and development-only, generation-deduplicated detail.
     *
     * <p>{@code activeGeneration} must be the exact value returned from {@link ClientModelRegistry#publish}; a stale
     * candidate therefore reports the actual active generation rather than its obsolete prepared result.</p>
     */
    synchronized void report(ModelRegistryGeneration candidateGeneration, ModelRegistryGeneration activeGeneration) {
        ModelRegistryGeneration candidate = Objects.requireNonNull(candidateGeneration, "candidateGeneration");
        ModelRegistryGeneration active = Objects.requireNonNull(activeGeneration, "activeGeneration");
        List<PrimaryDiagnostic> primaryDiagnostics = primaryDiagnostics(active);
        sink.reportSummary(new Summary(
                candidate.generationId(),
                active.generationId(),
                candidate == active,
                active.handles().size(),
                primaryDiagnostics.size(),
                active.diagnostics().size()));

        if (!sink.developmentDetailsEnabled()) {
            return;
        }
        if (active.generationId() < detailedGeneration) {
            return;
        }
        if (active.generationId() > detailedGeneration) {
            detailedGeneration = active.generationId();
            reportedGlobalDiagnostics.clear();
        }

        for (PrimaryDiagnostic primaryDiagnostic : primaryDiagnostics) {
            primaryDiagnosticDeduplicator
                    .firstForGeneration(
                            active.generationId(), primaryDiagnostic.modelKey(), primaryDiagnostic.diagnostic())
                    .ifPresent(diagnostic -> sink.reportDevelopmentDetail(Detail.primary(
                            active.generationId(), diagnostic)));
        }
        for (BlendDiagnostic globalDiagnostic : globalDiagnostics(active, primaryDiagnostics)) {
            if (reportedGlobalDiagnostics.add(globalDiagnostic)) {
                sink.reportDevelopmentDetail(Detail.global(active.generationId(), globalDiagnostic));
            }
        }
    }

    private static List<PrimaryDiagnostic> primaryDiagnostics(ModelRegistryGeneration generation) {
        List<PrimaryDiagnostic> diagnostics = new ArrayList<>();
        for (ModelHandle handle : generation.handles().values()) {
            if (handle instanceof MissingModelHandle missingModel) {
                diagnostics.add(new PrimaryDiagnostic(missingModel.key(), missingModel.diagnostic()));
            }
        }
        diagnostics.sort(Comparator.comparing(primary -> primary.modelKey().value()));
        return diagnostics;
    }

    private static List<BlendDiagnostic> globalDiagnostics(
            ModelRegistryGeneration generation, List<PrimaryDiagnostic> primaryDiagnostics) {
        Map<BlendDiagnostic, Integer> primaryCounts = new HashMap<>();
        for (PrimaryDiagnostic primaryDiagnostic : primaryDiagnostics) {
            primaryCounts.merge(primaryDiagnostic.diagnostic(), 1, Integer::sum);
        }

        List<BlendDiagnostic> globalDiagnostics = new ArrayList<>();
        for (BlendDiagnostic diagnostic : generation.diagnostics()) {
            Integer remainingPrimaryCount = primaryCounts.get(diagnostic);
            if (remainingPrimaryCount == null || remainingPrimaryCount == 0) {
                globalDiagnostics.add(diagnostic);
            } else if (remainingPrimaryCount == 1) {
                primaryCounts.remove(diagnostic);
            } else {
                primaryCounts.put(diagnostic, remainingPrimaryCount - 1);
            }
        }
        globalDiagnostics.sort(Comparator
                .comparing(BlendDiagnostic::code)
                .thenComparing(diagnostic -> nullableResourceIdValue(diagnostic.modelKey()))
                .thenComparing(diagnostic -> nullableResourceIdValue(diagnostic.resourceId()))
                .thenComparing(BlendDiagnostic::location)
                .thenComparing(BlendDiagnostic::message)
                .thenComparing(BlendDiagnostic::causeSummary)
                .thenComparing(diagnostic -> diagnostic.severity().name()));
        return globalDiagnostics;
    }

    private static String nullableResourceIdValue(BlendResourceId resourceId) {
        return resourceId == null ? "" : resourceId.value();
    }

    /** Package-private logging seam so reload tests never have to capture a JVM-global logger. */
    interface Sink {
        void reportSummary(Summary summary);

        boolean developmentDetailsEnabled();

        void reportDevelopmentDetail(Detail detail);
    }

    /** Immutable structured production summary for exactly one listener apply call. */
    record Summary(
            long candidateGeneration,
            long activeGeneration,
            boolean published,
            int modelCount,
            int missingCount,
            int diagnosticCount) {
        Summary {
            if (candidateGeneration < 0L || activeGeneration < 0L) {
                throw new IllegalArgumentException("generation identifiers must be non-negative");
            }
            if (modelCount < 0 || missingCount < 0 || diagnosticCount < 0) {
                throw new IllegalArgumentException("reload summary counts must be non-negative");
            }
        }

        boolean stale() {
            return !published;
        }

        String structuredMessage() {
            return "blendlib_reload candidate_generation=" + candidateGeneration
                    + " active_generation=" + activeGeneration
                    + " published=" + published
                    + " stale=" + stale()
                    + " models=" + modelCount
                    + " missing=" + missingCount
                    + " diagnostics=" + diagnosticCount;
        }
    }

    /** One bounded development-log view of a primary missing-model or global reload diagnostic. */
    record Detail(
            long generation,
            boolean primary,
            DiagnosticSeverity severity,
            String code,
            BlendResourceId modelKey,
            BlendResourceId resourceId,
            String location,
            String message,
            String causeSummary) {
        Detail {
            if (generation < 0L) {
                throw new IllegalArgumentException("generation must be non-negative");
            }
            severity = Objects.requireNonNull(severity, "severity");
            code = Objects.requireNonNull(code, "code");
            location = Objects.requireNonNull(location, "location");
            message = Objects.requireNonNull(message, "message");
            causeSummary = Objects.requireNonNull(causeSummary, "causeSummary");
        }

        static Detail primary(long generation, BlendDiagnostic diagnostic) {
            return from(generation, true, diagnostic);
        }

        static Detail global(long generation, BlendDiagnostic diagnostic) {
            return from(generation, false, diagnostic);
        }

        private static Detail from(long generation, boolean primary, BlendDiagnostic diagnostic) {
            BlendDiagnostic checkedDiagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
            return new Detail(
                    generation,
                    primary,
                    checkedDiagnostic.severity(),
                    checkedDiagnostic.code(),
                    checkedDiagnostic.modelKey(),
                    checkedDiagnostic.resourceId(),
                    checkedDiagnostic.location(),
                    checkedDiagnostic.message(),
                    checkedDiagnostic.causeSummary());
        }

        String structuredMessage() {
            return "blendlib_reload_diagnostic generation=" + generation
                    + " primary=" + primary
                    + " severity=" + severity
                    + " code=" + quote(code)
                    + " model_key=" + quoteNullable(modelKey)
                    + " resource_id=" + quoteNullable(resourceId)
                    + " location=" + quote(location)
                    + " message=" + quote(message)
                    + " cause_summary=" + quote(causeSummary);
        }

        private static String quoteNullable(BlendResourceId resourceId) {
            return resourceId == null ? "null" : quote(resourceId.value());
        }

        private static String quote(String value) {
            StringBuilder quoted = new StringBuilder(value.length() + 2).append('"');
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                switch (character) {
                    case '\\' -> quoted.append("\\\\");
                    case '"' -> quoted.append("\\\"");
                    case '\r' -> quoted.append("\\r");
                    case '\n' -> quoted.append("\\n");
                    case '\t' -> quoted.append("\\t");
                    default -> appendQuotedCharacter(quoted, character);
                }
            }
            return quoted.append('"').toString();
        }

        private static void appendQuotedCharacter(StringBuilder quoted, char character) {
            if (!Character.isISOControl(character)) {
                quoted.append(character);
                return;
            }
            String hex = Integer.toHexString(character);
            quoted.append("\\u").append("0000", 0, 4 - hex.length()).append(hex);
        }
    }

    /** Production sink backed by Java's platform logger; detail remains unavailable unless DEBUG is enabled. */
    private static final class SystemSink implements Sink {
        private final System.Logger logger;

        private SystemSink(System.Logger logger) {
            this.logger = Objects.requireNonNull(logger, "logger");
        }

        @Override
        public void reportSummary(Summary summary) {
            logger.log(System.Logger.Level.INFO, Objects.requireNonNull(summary, "summary").structuredMessage());
        }

        @Override
        public boolean developmentDetailsEnabled() {
            return logger.isLoggable(System.Logger.Level.DEBUG);
        }

        @Override
        public void reportDevelopmentDetail(Detail detail) {
            logger.log(System.Logger.Level.DEBUG, Objects.requireNonNull(detail, "detail").structuredMessage());
        }
    }

    private record PrimaryDiagnostic(BlendModelKey modelKey, BlendDiagnostic diagnostic) {
        private PrimaryDiagnostic {
            modelKey = Objects.requireNonNull(modelKey, "modelKey");
            diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
        }
    }
}
