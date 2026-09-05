package com.liy.blendlib.spi.experimental;

/**
 * Bounded semantic version used only by the experimental capability protocol.
 *
 * @param major protocol major component
 * @param minor protocol minor component
 * @param patch protocol patch component
 */
@ExperimentalBlendLibSpi
public record CapabilityVersion(int major, int minor, int patch) implements Comparable<CapabilityVersion> {
    /** Largest allowed component value, preventing unbounded malformed metadata. */
    public static final int MAX_COMPONENT = 1_000_000;

    /** Historical initial X1 capability data-plane version; it does not select a legacy host lifecycle policy. */
    public static final CapabilityVersion INITIAL_PROTOCOL = new CapabilityVersion(1, 0, 0);

    /** Current X1 host-contract version, including terminal exact-object handling for every {@link Error}. */
    public static final CapabilityVersion CURRENT_PROTOCOL = new CapabilityVersion(1, 1, 0);

    /** First protocol version excluded from the current X1/X6 opt-in range. */
    public static final CapabilityVersion CURRENT_PROTOCOL_MAX_EXCLUSIVE = new CapabilityVersion(1, 2, 0);

    /** Current X1/X6 opt-in range; a new host must not silently select a historical 1.0 offer. */
    public static final CapabilityVersionRange CURRENT_PROTOCOL_RANGE =
            new CapabilityVersionRange(CURRENT_PROTOCOL, CURRENT_PROTOCOL_MAX_EXCLUSIVE);

    /** Validates all three non-negative bounded version components. */
    public CapabilityVersion {
        validateComponent("major", major);
        validateComponent("minor", minor);
        validateComponent("patch", patch);
    }

    /**
     * Compares semantic components in major, minor, then patch order.
     *
     * @param other version to compare
     * @return negative, zero, or positive ordering value
     */
    @Override
    public int compareTo(CapabilityVersion other) {
        int majorComparison = Integer.compare(major, other.major);
        if (majorComparison != 0) {
            return majorComparison;
        }
        int minorComparison = Integer.compare(minor, other.minor);
        return minorComparison != 0 ? minorComparison : Integer.compare(patch, other.patch);
    }

    /**
     * Returns a canonical dotted version form.
     *
     * @return canonical dotted version
     */
    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }

    private static void validateComponent(String name, int value) {
        if (value < 0 || value > MAX_COMPONENT) {
            throw new IllegalArgumentException(name + " must be in [0, " + MAX_COMPONENT + "]");
        }
    }
}
