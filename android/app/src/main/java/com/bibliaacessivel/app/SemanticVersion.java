package com.bibliaacessivel.app;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Versão semântica comparada numericamente, sem ordenar textos como strings. */
final class SemanticVersion implements Comparable<SemanticVersion> {
    private static final Pattern PATTERN = Pattern.compile(
            "^(?:android-)?v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?$");

    final int major;
    final int minor;
    final int patch;
    final String prerelease;

    private SemanticVersion(int major, int minor, int patch, String prerelease) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.prerelease = prerelease == null ? "" : prerelease;
    }

    /** Aceita versões do aplicativo e tags Android como android-v2.0.0. */
    static SemanticVersion parse(String value) {
        Matcher match = PATTERN.matcher(value == null ? "" : value.trim());
        if (!match.matches()) throw new IllegalArgumentException("Versão semântica inválida.");
        return new SemanticVersion(
                Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)),
                Integer.parseInt(match.group(3)), match.group(4));
    }

    /** Ordena números primeiro e considera uma prévia anterior à versão estável. */
    @Override public int compareTo(SemanticVersion other) {
        int result = Integer.compare(major, other.major);
        if (result == 0) result = Integer.compare(minor, other.minor);
        if (result == 0) result = Integer.compare(patch, other.patch);
        if (result != 0) return result;
        if (prerelease.isEmpty() && other.prerelease.isEmpty()) return 0;
        if (prerelease.isEmpty()) return 1;
        if (other.prerelease.isEmpty()) return -1;
        return comparePrerelease(prerelease, other.prerelease);
    }

    /** Compara identificadores numéricos e textuais conforme a regra SemVer. */
    private static int comparePrerelease(String left, String right) {
        String[] leftParts = left.split("\\.");
        String[] rightParts = right.split("\\.");
        int length = Math.min(leftParts.length, rightParts.length);
        for (int index = 0; index < length; index++) {
            String a = leftParts[index];
            String b = rightParts[index];
            if (a.equals(b)) continue;
            boolean aNumber = a.matches("\\d+");
            boolean bNumber = b.matches("\\d+");
            if (aNumber && bNumber) return Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
            if (aNumber != bNumber) return aNumber ? -1 : 1;
            return a.compareTo(b);
        }
        return Integer.compare(leftParts.length, rightParts.length);
    }

    @Override public boolean equals(Object other) {
        return other instanceof SemanticVersion && compareTo((SemanticVersion) other) == 0;
    }

    @Override public int hashCode() {
        return Objects.hash(major, minor, patch, prerelease);
    }

    @Override public String toString() {
        return major + "." + minor + "." + patch + (prerelease.isEmpty() ? "" : "-" + prerelease);
    }
}
