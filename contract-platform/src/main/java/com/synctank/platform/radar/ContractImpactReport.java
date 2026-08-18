package com.synctank.platform.radar;

import com.synctank.platform.diff.ChangeRecord;
import com.synctank.platform.diff.Severity;

import java.util.List;

/**
 * What /diff returns from Day 06 onward.
 *
 * The first four components are a byte-for-byte JSON superset of Day 03's DiffReport, so
 * `jq -r '.highestSeverity'` in CI keeps working and /report can still deserialise the same
 * document. DiffReport itself is untouched — see §2.4 of the Day 06 guide.
 */
public record ContractImpactReport(
        boolean changed,
        Severity highestSeverity,
        List<ChangeRecord> changes,
        String markdown,
        EffectiveSeverity effectiveSeverity,
        List<ImpactAssessment> impact
) {}