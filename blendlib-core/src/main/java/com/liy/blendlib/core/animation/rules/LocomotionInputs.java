package com.liy.blendlib.core.animation.rules;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable, bounded snapshot of the caller's visual-only locomotion inputs.
 *
 * <p>Non-finite numeric values, null values, and keys present in both maps make
 * the snapshot invalid rather than throwing during extraction. A rules consumer
 * must check {@link LocomotionRules#inputsComplete(LocomotionInputs)} and preserve
 * its last state when inputs are invalid or incomplete.</p>
 */
public final class LocomotionInputs {
    public static final int MAX_INPUT_KEYS = 32;
    public static final int MAX_INPUT_NAME_LENGTH = 64;

    private final Map<String, Boolean> booleans;
    private final Map<String, Double> numbers;
    private final boolean valid;

    public LocomotionInputs(Map<String, Boolean> booleans, Map<String, Double> numbers) {
        Objects.requireNonNull(booleans, "booleans");
        Objects.requireNonNull(numbers, "numbers");
        if (booleans.size() > MAX_INPUT_KEYS || numbers.size() > MAX_INPUT_KEYS) {
            throw new IllegalArgumentException("Locomotion inputs exceed the 32-key limit");
        }
        LinkedHashMap<String, Boolean> booleanCopy = new LinkedHashMap<>();
        LinkedHashMap<String, Double> numberCopy = new LinkedHashMap<>();
        boolean validValues = true;
        for (Map.Entry<String, Boolean> entry : booleans.entrySet()) {
            booleanCopy.put(requireInputName(entry.getKey()), entry.getValue());
            validValues &= entry.getValue() != null;
        }
        int distinctKeys = booleanCopy.size();
        for (Map.Entry<String, Double> entry : numbers.entrySet()) {
            String name = requireInputName(entry.getKey());
            numberCopy.put(name, entry.getValue());
            if (booleanCopy.containsKey(name)) {
                validValues = false;
            } else {
                distinctKeys++;
            }
            validValues &= entry.getValue() != null && Double.isFinite(entry.getValue());
        }
        if (distinctKeys > MAX_INPUT_KEYS) {
            throw new IllegalArgumentException("Locomotion inputs exceed the 32-key limit");
        }
        this.booleans = Collections.unmodifiableMap(booleanCopy);
        this.numbers = Collections.unmodifiableMap(numberCopy);
        this.valid = validValues;
    }

    public Map<String, Boolean> booleans() {
        return booleans;
    }

    public Map<String, Double> numbers() {
        return numbers;
    }

    /** Whether all captured values are non-null, finite, and have one type each. */
    public boolean valid() {
        return valid;
    }

    static String requireInputName(String name) {
        Objects.requireNonNull(name, "input name");
        if (name.isEmpty() || name.length() > MAX_INPUT_NAME_LENGTH || !identifierStart(name.charAt(0))) {
            throw new IllegalArgumentException("Locomotion input name must be a 1..64-character ASCII identifier");
        }
        for (int index = 1; index < name.length(); index++) {
            char character = name.charAt(index);
            if (!identifierStart(character) && (character < '0' || character > '9')) {
                throw new IllegalArgumentException("Locomotion input name must be a 1..64-character ASCII identifier");
            }
        }
        return name;
    }

    private static boolean identifierStart(char character) {
        return character == '_' || character >= 'a' && character <= 'z' || character >= 'A' && character <= 'Z';
    }
}
