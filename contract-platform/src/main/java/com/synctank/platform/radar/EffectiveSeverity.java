package com.synctank.platform.radar;

/**
 * Severity AFTER the Impact Radar has weighed real consumers.
 *
 * Deliberately a separate enum from Day 03's Severity rather than extra constants on it:
 * Severity is the deterministic classifier's verdict about the contract itself and must
 * stay stable and testable. EffectiveSeverity is a product judgement layered on top, and
 * mixing the two would let a registry outage change what "BREAKING" means.
 */
public enum EffectiveSeverity {
    /** Breaking on paper, but no registered client compiles against it. */
    SAFE_WITH_NOTE(0),
    ADDITIVE(1),
    DANGEROUS(2),
    BREAKING(3);

    private final int rank;

    EffectiveSeverity(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }
}
