package com.synctank.platform.radar;

import com.synctank.platform.diff.Severity;

import java.util.List;

/**
 * The radar's verdict for one change. Both severities are reported side by side on purpose:
 * hiding the classifier's original call would make the platform look like it was guessing.
 */
public record ImpactAssessment(
        String location,
        Severity classifiedSeverity,       // Day 03's deterministic verdict — never overwritten
        EffectiveSeverity effectiveSeverity,
        String verdict,                    // one sentence explaining any re-weighting
        List<ConsumerImpact> consumers,
        long totalCallsPerDay
) {}