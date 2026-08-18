package com.synctank.platform.report;

import com.synctank.platform.diff.ChangeRecord;                 // Day 03
import com.synctank.platform.radar.ConsumerImpact;              // Day 06
import com.synctank.platform.radar.ContractImpactReport;        // Day 06
import com.synctank.platform.radar.ImpactAssessment;            // Day 06
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ChangeReportService {

    private final ChatClient chatClient;
    private final UsageScanner usageScanner;

    public ChangeReportService(ChatClient.Builder chatClientBuilder, UsageScanner usageScanner) {
        this.chatClient = chatClientBuilder.build();
        this.usageScanner = usageScanner;
    }

    /**
     * Day 06 change: takes ContractImpactReport instead of DiffReport, and returns
     * ContractChangeReport instead of ChangeReport. Everything Day 05 did is preserved —
     * the file scan, the severity-is-not-the-AI's-business rule, and the fallback path.
     */
    public ContractChangeReport generateReport(ContractImpactReport diffReport, Path frontendSrcRoot) {

        // Step 1 — deterministic: scan real usages per changed field (never delegated to the AI)
        Map<ChangeRecord, List<String>> usagesByChange = diffReport.changes().stream()
                .collect(Collectors.toMap(
                        change -> change,
                        change -> usageScanner.findUsages(frontendSrcRoot, leafFieldName(change.location()))
                ));

        // Step 2 — deterministic: radar findings, keyed by the same location string
        Map<String, ImpactAssessment> impactByLocation = diffReport.impact().stream()
                .collect(Collectors.toMap(ImpactAssessment::location, Function.identity(), (a, b) -> a));

        // Step 3 — build a plain-text description of diff + usages + blast radius for the prompt.
        // Day 03's severity AND the radar's numbers go in as FACT. The AI adds narrative only.
        String changesBlock = diffReport.changes().stream()
                .map(change -> """
                        - location: %s
                          severity: %s (already classified — do not change this)
                          effective severity after Impact Radar: %s
                          category: %s
                          Day 03's own description: %s
                          radar verdict: %s
                          registered consumers: %s
                          usages found in frontend: %s
                        """.formatted(
                        change.location(),
                        change.severity(),
                        describeEffective(impactByLocation.get(change.location())),
                        change.category(),
                        change.description(),
                        describeVerdict(impactByLocation.get(change.location())),
                        describeConsumers(impactByLocation.get(change.location())),
                        usagesByChange.get(change).isEmpty()
                                ? "none found"
                                : String.join("; ", usagesByChange.get(change))
                ))
                .collect(Collectors.joining("\n"));

        String systemPrompt = """
                You are writing a pull-request comment for a Spring Boot + Angular API contract change.
                You are given a list of API changes that have ALREADY been classified as BREAKING,
                DANGEROUS, or ADDITIVE by a deterministic rules engine, along with that engine's own
                one-line description of each change — never re-classify severity yourself, and treat the
                existing description as accurate. Your job is to add value on top of it: mention specific
                frontend files affected (from the usage list given), and only where genuinely useful,
                restate the description in a slightly more narrative PR-comment voice.

                You are also given Consumer Impact Radar data: which registered applications, teams and
                screens consume each changed location, and how many calls per day those endpoints serve.
                These numbers are facts from a registry — quote them, never estimate, extrapolate, or
                invent them. If a change shows no registered consumers, say exactly that; do not soften
                it into "probably low impact" or "likely unused".

                When a change's effective severity differs from its classified severity, explain the
                reason in one clause, e.g. "breaking on paper, but no registered client compiles
                against it", or "escalated because the affected endpoint serves 12,000 calls/day".

                If frontend usages were found, mention the specific file(s). If none were found, say so
                plainly — do not invent usages.

                Only for BREAKING or DANGEROUS changes involving a field rename or type change, suggest a
                minimal TypeScript migration snippet showing the old access pattern and the new one side by
                side. If a change is ADDITIVE or you are not confident in a correct patch, leave
                suggestedMigrationPatch as an empty string rather than guessing.

                Keep the overall summary to 1-3 sentences. List anything you are unsure about in openQuestions
                instead of asserting it.
                """;

        String userPrompt = "API changes detected in this pull request:\n\n" + changesBlock;

        try {
            ChangeReport aiReport = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .entity(ChangeReport.class);

            // The radar list is attached AFTER the call, from the deterministic source.
            // Nothing the model returns can alter consumer names or call volumes.
            return new ContractChangeReport(
                    aiReport.summary(),
                    aiReport.changes(),
                    aiReport.suggestedMigrationPatch(),
                    aiReport.openQuestions(),
                    diffReport.impact());

        } catch (Exception e) {
            // AI narration is advisory only — a transient upstream failure (rate limit, retry
            // timeout, network hiccup) should never turn into a raw 500 with no useful content.
            // Day 06: the radar data survives the fallback intact, because it never came from
            // the AI in the first place. A degraded report still names the affected screens.
            List<ChangeExplanation> fallbackChanges = diffReport.changes().stream()
                    .map(change -> new ChangeExplanation(
                            change.location(),
                            change.severity().toString(),
                            change.description(),
                            usagesByChange.getOrDefault(change, List.of())
                    ))
                    .toList();

            return new ContractChangeReport(
                    "Automated AI narration failed this run (%s) — showing the deterministic classification and Impact Radar data directly."
                            .formatted(e.getClass().getSimpleName()),
                    fallbackChanges,
                    "",
                    List.of("AI-generated summary and migration suggestion were unavailable this run — the Impact Radar findings below are unaffected."),
                    diffReport.impact());
        }
    }

    private static String describeEffective(ImpactAssessment assessment) {
        return assessment == null ? "not assessed" : assessment.effectiveSeverity().toString();
    }

    private static String describeVerdict(ImpactAssessment assessment) {
        return assessment == null ? "no radar data" : assessment.verdict();
    }

    private static String describeConsumers(ImpactAssessment assessment) {
        if (assessment == null || assessment.consumers().isEmpty()) {
            return "none registered";
        }
        return assessment.consumers().stream()
                .map(ChangeReportService::describeConsumer)
                .collect(Collectors.joining("; "));
    }

    private static String describeConsumer(ConsumerImpact consumer) {
        return "%s (team: %s, screens: %s, %d calls/day)".formatted(
                consumer.appName(),
                consumer.team() == null ? "unknown" : consumer.team(),
                consumer.screens().isEmpty() ? "unmapped" : String.join(", ", consumer.screens()),
                consumer.callsPerDay());
    }

    // Day 03's ChangeRecord.location() is either "OrderResponse.amount"-style (field changes) or
    // "GET /orders/{id}"-style (endpoint changes). For the latter there's no single field name to
    // grep for, so we fall back to the raw location string — the scanner will simply find nothing,
    // which is a correct (if unhelpful) result for an endpoint-level change.
    private static String leafFieldName(String location) {
        int dot = location.lastIndexOf('.');
        return dot == -1 ? location : location.substring(dot + 1);
    }
}