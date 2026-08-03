package com.liy.blendlib.core.profile.experimental;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict, bounded semantic-version value used only by the X9 capability envelope. */
public record ExperimentalSemVer(int major, int minor, int patch) implements Comparable<ExperimentalSemVer> {
    private static final Pattern PATTERN = Pattern.compile("(0|[1-9][0-9]{0,4})\\.(0|[1-9][0-9]{0,4})\\.(0|[1-9][0-9]{0,4})");

    public ExperimentalSemVer {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("Semantic-version components must be non-negative");
        }
    }

    public static ExperimentalSemVer parse(String value) {
        Objects.requireNonNull(value, "value");
        Matcher matcher = PATTERN.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Expected a bounded semantic version major.minor.patch: " + value);
        }
        return new ExperimentalSemVer(
                Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)));
    }

    @Override
    public int compareTo(ExperimentalSemVer other) {
        int majorComparison = Integer.compare(major, other.major);
        if (majorComparison != 0) {
            return majorComparison;
        }
        int minorComparison = Integer.compare(minor, other.minor);
        return minorComparison != 0 ? minorComparison : Integer.compare(patch, other.patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
