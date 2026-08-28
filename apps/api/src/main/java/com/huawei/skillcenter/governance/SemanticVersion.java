package com.huawei.skillcenter.governance;

import java.math.BigInteger;
import java.util.List;
import java.util.regex.Pattern;

final class SemanticVersion implements Comparable<SemanticVersion> {
    private static final Pattern VALID = Pattern.compile(
            "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"
                    + "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?"
                    + "(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$");
    private static final Pattern NUMERIC_IDENTIFIER = Pattern.compile("^(0|[1-9][0-9]*)$");

    private final BigInteger major;
    private final BigInteger minor;
    private final BigInteger patch;
    private final List<String> preRelease;

    private SemanticVersion(BigInteger major, BigInteger minor, BigInteger patch, List<String> preRelease) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.preRelease = preRelease;
    }

    static SemanticVersion parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("SemVer must not be null");
        }
        var matcher = VALID.matcher(value.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid SemVer: " + value);
        }
        String preRelease = matcher.group(4);
        return new SemanticVersion(new BigInteger(matcher.group(1)), new BigInteger(matcher.group(2)),
                new BigInteger(matcher.group(3)), preRelease == null ? List.of() : List.of(preRelease.split("\\.")));
    }

    @Override
    public int compareTo(SemanticVersion other) {
        int core = major.compareTo(other.major);
        if (core != 0) return core;
        core = minor.compareTo(other.minor);
        if (core != 0) return core;
        core = patch.compareTo(other.patch);
        if (core != 0) return core;
        if (preRelease.isEmpty() && other.preRelease.isEmpty()) return 0;
        if (preRelease.isEmpty()) return 1;
        if (other.preRelease.isEmpty()) return -1;
        for (int index = 0; index < Math.min(preRelease.size(), other.preRelease.size()); index++) {
            String left = preRelease.get(index);
            String right = other.preRelease.get(index);
            if (left.equals(right)) continue;
            boolean leftNumeric = NUMERIC_IDENTIFIER.matcher(left).matches();
            boolean rightNumeric = NUMERIC_IDENTIFIER.matcher(right).matches();
            if (leftNumeric && rightNumeric) {
                return new BigInteger(left).compareTo(new BigInteger(right));
            }
            if (leftNumeric != rightNumeric) return leftNumeric ? -1 : 1;
            return left.compareTo(right);
        }
        return Integer.compare(preRelease.size(), other.preRelease.size());
    }
}
