package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Builds deterministic frozen X6 selection plans only during a caller's prepare/reload seam. */
public final class X6VariantSelectionEngine {
    private static final Comparator<X6Diagnostic> DIAGNOSTIC_ORDER = Comparator
            .comparing((X6Diagnostic value) -> value.code().code())
            .thenComparing(value -> value.subjectId().map(BlendResourceId::value).orElse(""))
            .thenComparing(X6Diagnostic::message);

    private X6VariantSelectionEngine() {
    }

    /**
     * Resolves code-side or data-side immutable input through one deterministic selector.
     * Parsing is intentionally absent: callers that use a manifest must parse it before this method.
     */
    public static X6PlanResult<X6VariantSelectionPlan> prepare(
            BlendModelKey modelKey, long generation, X6VariantManifest manifest, X6VariantContext context) {
        modelKey = Objects.requireNonNull(modelKey, "modelKey");
        manifest = Objects.requireNonNull(manifest, "manifest");
        context = Objects.requireNonNull(context, "context");
        if (generation < 0L) {
            throw new IllegalArgumentException("generation must be non-negative");
        }

        List<X6Diagnostic> diagnostics = new ArrayList<>();
        if (manifest.formatVersion() != X6VariantManifest.FORMAT_VERSION) {
            diagnostics.add(X6Diagnostic.error(
                    X6DiagnosticCode.MANIFEST_INVALID,
                    modelKey,
                    generation,
                    null,
                    "X6 variant manifest format_version must be " + X6VariantManifest.FORMAT_VERSION));
        }
        if (manifest.variants().size() > X6Ids.MAX_VARIANTS) {
            diagnostics.add(X6Diagnostic.error(
                    X6DiagnosticCode.VARIANT_LIMIT,
                    modelKey,
                    generation,
                    null,
                    "X6 variant count exceeds " + X6Ids.MAX_VARIANTS));
        }
        if (manifest.rules().size() > X6Ids.MAX_RULES) {
            diagnostics.add(X6Diagnostic.error(
                    X6DiagnosticCode.VARIANT_LIMIT,
                    modelKey,
                    generation,
                    null,
                    "X6 variant rule count exceeds " + X6Ids.MAX_RULES));
        }

        Map<BlendResourceId, X6VariantDefinition> variants = new LinkedHashMap<>();
        Set<BlendResourceId> partIds = new HashSet<>();
        for (X6VariantDefinition variant : manifest.variants().stream()
                .sorted(Comparator.comparing(value -> value.variantId().value()))
                .toList()) {
            X6VariantDefinition previous = variants.putIfAbsent(variant.variantId(), variant);
            if (previous != null) {
                diagnostics.add(X6Diagnostic.error(
                        X6DiagnosticCode.VARIANT_DUPLICATE,
                        modelKey,
                        generation,
                        variant.variantId(),
                        "Duplicate X6 variant id"));
            }
            partIds.add(variant.partId());
        }
        if (partIds.size() > X6Ids.MAX_PARTS) {
            diagnostics.add(X6Diagnostic.error(
                    X6DiagnosticCode.VARIANT_LIMIT,
                    modelKey,
                    generation,
                    null,
                    "X6 variant part count exceeds " + X6Ids.MAX_PARTS));
        }

        Map<BlendResourceId, X6VariantRule> rules = new LinkedHashMap<>();
        for (X6VariantRule rule : manifest.rules().stream()
                .sorted(Comparator.comparing(value -> value.ruleId().value()))
                .toList()) {
            X6VariantRule previous = rules.putIfAbsent(rule.ruleId(), rule);
            if (previous != null) {
                diagnostics.add(X6Diagnostic.error(
                        X6DiagnosticCode.VARIANT_DUPLICATE,
                        modelKey,
                        generation,
                        rule.ruleId(),
                        "Duplicate X6 variant rule id"));
            }
            if (!variants.containsKey(rule.variantId())) {
                diagnostics.add(X6Diagnostic.error(
                        X6DiagnosticCode.VARIANT_UNKNOWN,
                        modelKey,
                        generation,
                        rule.variantId(),
                        "Variant rule references an unknown variant"));
            }
        }
        for (Map.Entry<BlendResourceId, BlendResourceId> entry : manifest.defaults().entrySet()) {
            if (!variants.containsKey(entry.getValue())) {
                diagnostics.add(X6Diagnostic.error(
                        X6DiagnosticCode.VARIANT_UNKNOWN,
                        modelKey,
                        generation,
                        entry.getValue(),
                        "Variant default references an unknown variant"));
            }
        }

        List<X6VariantSelection> selections = select(modelKey, generation, manifest, variants, rules.values(), context, diagnostics);
        diagnostics.sort(DIAGNOSTIC_ORDER);
        if (diagnostics.stream().anyMatch(value -> value.severity() == X6DiagnosticSeverity.ERROR)) {
            return X6PlanResult.failure(diagnostics);
        }
        return X6PlanResult.success(new X6VariantSelectionPlan(modelKey, generation, variants, selections), diagnostics);
    }

    private static List<X6VariantSelection> select(
            BlendModelKey modelKey,
            long generation,
            X6VariantManifest manifest,
            Map<BlendResourceId, X6VariantDefinition> variants,
            java.util.Collection<X6VariantRule> rules,
            X6VariantContext context,
            List<X6Diagnostic> diagnostics) {
        Set<BlendResourceId> selectors = new HashSet<>(manifest.defaults().keySet());
        rules.forEach(rule -> selectors.add(rule.selectorId()));
        List<X6VariantSelection> selections = new ArrayList<>(selectors.size());
        for (BlendResourceId selector : selectors.stream().sorted(Comparator.comparing(BlendResourceId::value)).toList()) {
            List<X6VariantRule> matching = rules.stream()
                    .filter(rule -> rule.selectorId().equals(selector) && variants.containsKey(rule.variantId()))
                    .filter(rule -> rule.matches(context))
                    .sorted(Comparator.comparingInt(X6VariantRule::priority).reversed()
                            .thenComparing(rule -> rule.ruleId().value()))
                    .toList();
            if (!matching.isEmpty()) {
                X6VariantRule winner = matching.getFirst();
                long topTies = matching.stream().filter(rule -> rule.priority() == winner.priority()).count();
                if (topTies > 1L) {
                    diagnostics.add(X6Diagnostic.error(
                            X6DiagnosticCode.VARIANT_CONFLICT,
                            modelKey,
                            generation,
                            selector,
                            "Multiple matching X6 rules tie at highest priority; registration order is not a winner"));
                    continue;
                }
                selections.add(new X6VariantSelection(
                        selector, X6VariantRuleOutcome.MATCHED, java.util.Optional.of(winner.variantId())));
                continue;
            }
            BlendResourceId defaultVariant = manifest.defaults().get(selector);
            if (defaultVariant != null && variants.containsKey(defaultVariant)) {
                selections.add(new X6VariantSelection(
                        selector, X6VariantRuleOutcome.DEFAULTED, java.util.Optional.of(defaultVariant)));
                continue;
            }
            diagnostics.add(X6Diagnostic.warning(
                    X6DiagnosticCode.VARIANT_NO_MATCH,
                    modelKey,
                    generation,
                    selector,
                    "No X6 rule matched and no default exists; the prepared baseline is retained"));
            selections.add(new X6VariantSelection(
                    selector, X6VariantRuleOutcome.BASELINE_FALLBACK, java.util.Optional.empty()));
        }
        return List.copyOf(selections);
    }
}
