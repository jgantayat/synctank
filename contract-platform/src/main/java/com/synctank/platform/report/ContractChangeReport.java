package com.synctank.platform.report;

import com.synctank.platform.radar.ImpactAssessment;

import java.util.List;

/**
 * What /report returns from Day 06 onward: everything the AI wrote, plus the radar's
 * deterministic findings.
 *
 * The radar data is a SEPARATE field rather than extra components on ChangeReport, because
 * ChangeReport is Spring AI's structured-output target — widening it would make the model
 * responsible for filling in consumer names and call volumes, which is precisely the data
 * it must never invent. The AI is told the numbers; it is never asked to produce them.
 */
public record ContractChangeReport(
        String summary,
        List<ChangeExplanation> changes,
        String suggestedMigrationPatch,
        List<String> openQuestions,
        List<ImpactAssessment> impact
) {}