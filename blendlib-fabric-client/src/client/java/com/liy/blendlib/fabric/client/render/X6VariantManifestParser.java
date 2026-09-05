package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.api.BlendResourceId;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Strict parser for the isolated {@code blendlib-x6-variant-manifest-v1} test/prepare input.
 *
 * <p>It is deliberately not a descriptor decoder, does not retain generic v1 extensions, and must
 * only be invoked by a reload/prepare owner before {@link X6VariantSelectionEngine#prepare}.
 */
public final class X6VariantManifestParser {
    private X6VariantManifestParser() {
    }

    /**
     * Decodes and parses one bounded byte source before any snapshot or submit operation exists.
     *
     * <p>The byte cap is enforced before decode, malformed or unmappable UTF-8 is rejected, and a
     * UTF-8 BOM is deliberately not accepted as grammar whitespace. Reload owners should use this
     * overload; it is the only form that can prove a source-byte limit.</p>
     */
    public static X6VariantManifest parseForPrepare(byte[] sourceBytes) {
        sourceBytes = Objects.requireNonNull(sourceBytes, "sourceBytes");
        if (sourceBytes.length > X6Ids.MAX_MANIFEST_BYTES) {
            throw invalid("manifest exceeds the hard byte limit");
        }
        final String source;
        try {
            source = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(sourceBytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw invalid("manifest is not strict UTF-8");
        }
        if (source.startsWith("\uFEFF")) {
            throw invalid("manifest must not contain a UTF-8 BOM");
        }
        return parseDecoded(source);
    }

    /**
     * Convenience entry point for code-owned text only.
     *
     * <p>A {@link String} no longer retains its source encoding, so this overload cannot prove the
     * original byte stream was strict UTF-8. It measures the canonical UTF-8 re-encoding and
     * rejects a BOM character; resource/reload callers must use {@link #parseForPrepare(byte[])}.
     */
    public static X6VariantManifest parseForPrepare(String source) {
        source = Objects.requireNonNull(source, "source");
        if (source.getBytes(StandardCharsets.UTF_8).length > X6Ids.MAX_MANIFEST_BYTES) {
            throw invalid("manifest exceeds the hard canonical UTF-8 byte limit");
        }
        if (source.startsWith("\uFEFF")) {
            throw invalid("manifest must not contain a UTF-8 BOM");
        }
        return parseDecoded(source);
    }

    private static X6VariantManifest parseDecoded(String source) {
        String[] lines = source.split("\\R", -1);
        if (lines.length > X6Ids.MAX_MANIFEST_LINES) {
            throw invalid("manifest exceeds the hard line limit");
        }

        Integer version = null;
        List<X6VariantDefinition> variants = new ArrayList<>();
        List<X6VariantRule> rules = new ArrayList<>();
        Map<BlendResourceId, BlendResourceId> defaults = new LinkedHashMap<>();
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            int lineNumber = index + 1;
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (!line.equals(line.trim())) {
                throw invalid("line " + lineNumber + " has leading or trailing whitespace");
            }
            int equals = line.indexOf('=');
            if (equals <= 0) {
                throw invalid("line " + lineNumber + " must contain a field separator");
            }
            String key = line.substring(0, equals);
            String value = line.substring(equals + 1);
            if (value.isEmpty()) {
                throw invalid("line " + lineNumber + " has an empty value");
            }
            // A rule's final condition token deliberately contains one or more key=value pairs.
            // Every other top-level field keeps the strict single-separator grammar.
            if (!key.equals("rule") && equals != line.lastIndexOf('=')) {
                throw invalid("line " + lineNumber + " must contain exactly one field separator");
            }
            switch (key) {
                case "format_version" -> {
                    if (version != null) {
                        throw invalid("format_version may occur once");
                    }
                    version = parsePositiveInt(value, lineNumber, "format_version");
                }
                case "variant" -> variants.add(parseVariant(value, lineNumber));
                case "default" -> parseDefault(value, lineNumber, defaults);
                case "rule" -> rules.add(parseRule(value, lineNumber));
                default -> throw invalid("line " + lineNumber + " uses an unknown field " + key);
            }
        }
        if (version == null) {
            throw invalid("format_version is required");
        }
        return new X6VariantManifest(version, variants, rules, defaults);
    }

    private static X6VariantDefinition parseVariant(String value, int lineNumber) {
        String[] values = splitExact(value, 3, lineNumber, "variant");
        try {
            return new X6VariantDefinition(
                    BlendResourceId.parse(values[0]), X6VariantKind.valueOf(values[1]), BlendResourceId.parse(values[2]));
        } catch (RuntimeException exception) {
            throw invalid("line " + lineNumber + " has an invalid variant declaration");
        }
    }

    private static void parseDefault(String value, int lineNumber, Map<BlendResourceId, BlendResourceId> defaults) {
        String[] values = splitExact(value, 2, lineNumber, "default");
        try {
            BlendResourceId selector = BlendResourceId.parse(values[0]);
            BlendResourceId variant = BlendResourceId.parse(values[1]);
            if (defaults.put(selector, variant) != null) {
                throw invalid("line " + lineNumber + " repeats a default selector");
            }
        } catch (IllegalArgumentException exception) {
            throw invalid("line " + lineNumber + " has an invalid default declaration");
        }
    }

    private static X6VariantRule parseRule(String value, int lineNumber) {
        String[] values = splitExact(value, 5, lineNumber, "rule");
        try {
            return new X6VariantRule(
                    BlendResourceId.parse(values[0]),
                    BlendResourceId.parse(values[1]),
                    Integer.parseInt(values[2]),
                    parseConditions(values[4], lineNumber),
                    BlendResourceId.parse(values[3]));
        } catch (RuntimeException exception) {
            throw invalid("line " + lineNumber + " has an invalid rule declaration");
        }
    }

    private static Map<BlendResourceId, String> parseConditions(String source, int lineNumber) {
        if (source.equals("-")) {
            return Map.of();
        }
        Map<BlendResourceId, String> conditions = new LinkedHashMap<>();
        for (String term : source.split(";", -1)) {
            int equals = term.indexOf('=');
            if (equals <= 0 || equals != term.lastIndexOf('=')) {
                throw invalid("line " + lineNumber + " has an invalid rule condition");
            }
            BlendResourceId key = BlendResourceId.parse(term.substring(0, equals));
            String previous = conditions.put(key, X6Ids.requireContextValue(term.substring(equals + 1), "condition"));
            if (previous != null) {
                throw invalid("line " + lineNumber + " repeats a rule condition key");
            }
        }
        return conditions;
    }

    private static String[] splitExact(String value, int count, int lineNumber, String field) {
        String[] values = value.split("\\|", -1);
        if (values.length != count) {
            throw invalid("line " + lineNumber + " " + field + " field count is invalid");
        }
        for (String token : values) {
            if (token.isEmpty() || !token.equals(token.trim())) {
                throw invalid("line " + lineNumber + " contains an empty or padded " + field + " token");
            }
        }
        return values;
    }

    private static int parsePositiveInt(String value, int lineNumber, String field) {
        try {
            if (!value.matches("[0-9]+")) {
                throw new NumberFormatException();
            }
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw invalid("line " + lineNumber + " has invalid " + field);
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("BLENDLIB-X6-MANIFEST-001: " + message);
    }
}
