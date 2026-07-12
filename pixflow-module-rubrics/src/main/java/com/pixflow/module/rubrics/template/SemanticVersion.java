package com.pixflow.module.rubrics.template;

import java.util.regex.Pattern;

public record SemanticVersion(int major, int minor, int patch) implements Comparable<SemanticVersion> {
    private static final Pattern PATTERN = Pattern.compile("(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)");

    public static SemanticVersion parse(String value) {
        var matcher = PATTERN.matcher(value == null ? "" : value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("template version must be semantic major.minor.patch: " + value);
        }
        return new SemanticVersion(
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)));
    }

    @Override
    public int compareTo(SemanticVersion other) {
        int majorOrder = Integer.compare(major, other.major);
        if (majorOrder != 0) return majorOrder;
        int minorOrder = Integer.compare(minor, other.minor);
        return minorOrder != 0 ? minorOrder : Integer.compare(patch, other.patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
