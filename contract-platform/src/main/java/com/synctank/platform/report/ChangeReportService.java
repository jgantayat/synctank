package com.synctank.platform.report;

import java.util.LinkedHashMap;
import com.synctank.platform.diff.ChangeRecord;                 // Day 03
import com.synctank.platform.radar.ConsumerImpact;              // Day 06
import com.synctank.platform.radar.ContractImpactReport;        // Day 06
import com.synctank.platform.radar.ImpactAssessment;            // Day 06
import com.synctank.platform.ai.AiUsageMeter;                   // Day 11
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ChangeReportService {

    /** The placeholder application.yaml uses when ANTHROPIC_API_KEY is not set (Day 09 F3). */
    static final String UNCONFIGURED_KEY = "not-configured";

    static final String NOT_CONFIGURED_SUMMARY = "AI narration is not configured on this platform "
            + "(no ANTHROPIC_API_KEY) — showing the deterministic classification and Impact Radar "
            + "data directly.";

    private final ChatClient chatClient;
    private final UsageScanner usageScanner;
    private final AiUsageMeter aiUsage;
    private final boolean aiConfigured;

    /**
     * Pre-Day-13 (A2) — the platform now knows when it has no key. Before, it called Anthropic
     * with the literal placeholder "not-configured", received a 401, spent one unit of the Day 11
     * daily cap on it, and reported "AI narration failed this run (UnauthorizedException)" —
     * a failure message for something that was never attempted on purpose.
     */
    @Autowired
    public ChangeReportService(ChatClient.Builder chatClientBuilder, UsageScanner usageScanner,
                               AiUsageMeter aiUsage,
                               @Value("${spring.ai.anthropic.api-key:" + UNCONFIGURED_KEY + "}") String apiKey) {
        this(chatClientBuilder, usageScanner, aiUsage, isConfigured(apiKey));
    }

    /** Existing tests' constructor: behaves exactly as before today (a key is assumed). */
    public ChangeReportService(ChatClient.Builder chatClientBuilder, UsageScanner usageScanner,
                               AiUsageMeter aiUsage) {
        this(chatClientBuilder, usageScanner, aiUsage, true);
    }

    ChangeReportService(ChatClient.Builder chatClientBuilder, UsageScanner usageScanner,
                        AiUsageMeter aiUsage, boolean aiConfigured) {
        this.chatClient = chatClientBuilder.build();
        this.usageScanner = usageScanner;
        this.aiUsage = aiUsage;
        this.aiConfigured = aiConfigured;
    }

    static boolean isConfigured(String apiKey) {
        return apiKey != null && !apiKey.isBlank() && !UNCONFIGURED_KEY.equals(apiKey.trim());
    }

    /**
     * Day 06 change: takes ContractImpactReport instead of DiffReport, and returns
     * ContractChangeReport instead of ChangeReport. Everything Day 05 did is preserved —
     * the file scan, the severity-is-not-the-AI's-business rule, and the fallback path.
     */
    public ContractChangeReport generateReport(ContractImpactReport diffReport, Path frontendSrcRoot) {

        // Step 1 — deterministic: scan real usages per changed field (never delegated to the AI)
        // Day 10 (F6, carried from Day 09's F5) — merge function + LinkedHashMap. ChangeRecord is
        // a record, so two identical records are EQUAL keys, and toMap without a merge function
        // throws IllegalStateException("Duplicate key") — outside the try block below, i.e. a raw
        // 500 from /report instead of the deterministic fallback this method promises.
        Map<ChangeRecord, List<String>> usagesByChange = diffReport.changes().stream()
                .collect(Collectors.toMap(
                        change -> change,
                        // Pre-Day-13 (B3) — endpoint-level locations ("GET /api/orders/{id}") are
                        // not symbols. Grepping for one found nothing at best, and at worst fed
                        // `{id}` to ripgrep as a regex repetition and got its error back as a hit.
                        change -> isEndpointLocation(change.location())
                                ? List.<String>of()
                                : usageScanner.findUsages(frontendSrcRoot, leafFieldName(change.location())),
                        (first, duplicate) -> first,       // identical records: one scan is enough
                        LinkedHashMap::new                 // keep diff order
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

        if (!aiConfigured) {
            return deterministicReport(diffReport, usagesByChange, NOT_CONFIGURED_SUMMARY, List.of());
        }

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
            // Day 11 — the only change here is the wrapper. A tripped cap throws
            // BudgetExceededException from inside this try, so the existing catch below
            // produces the deterministic report, exactly as it does for a rate limit or a
            // timeout. CI never hard-fails on a spend cap any more than on an AI outage.
            ChangeReport aiReport = aiUsage.meter("change-report", () -> chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .entity(ChangeReport.class));

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
            return deterministicReport(diffReport, usagesByChange,
                    "Automated AI narration failed this run (%s) — showing the deterministic classification and Impact Radar data directly."
                            .formatted(e.getClass().getSimpleName()),
                    List.of("AI-generated summary and migration suggestion were unavailable this run — the Impact Radar findings below are unaffected."));
        }
    }

    /** Day 03's descriptions + the real usage hits + the radar list. Nothing from a model. */
    private static ContractChangeReport deterministicReport(ContractImpactReport diffReport,
                                                            Map<ChangeRecord, List<String>> usagesByChange,
                                                            String summary, List<String> openQuestions) {
        List<ChangeExplanation> changes = diffReport.changes().stream()
                .map(change -> new ChangeExplanation(
                        change.location(),
                        change.severity().toString(),
                        change.description(),
                        usagesByChange.getOrDefault(change, List.of())
                ))
                .toList();
        return new ContractChangeReport(summary, changes, "", openQuestions, diffReport.impact());
    }

    /** Day 03 endpoint locations are "METHOD /path"; field locations never contain a space. */
    static boolean isEndpointLocation(String location) {
        return location != null && location.contains(" ");
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