package com.synctank.platform.report;

import com.synctank.platform.diff.ChangeRecord;   // Day 03 — actual class
import com.synctank.platform.diff.DiffReport;      // Day 03 — actual class
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ChangeReportService {

    private final ChatClient chatClient;
    private final UsageScanner usageScanner;

    public ChangeReportService(ChatClient.Builder chatClientBuilder, UsageScanner usageScanner) {
        this.chatClient = chatClientBuilder.build();
        this.usageScanner = usageScanner;
    }

    public ChangeReport generateReport(DiffReport diffReport, Path frontendSrcRoot) {

        // Step 1 — deterministic: scan real usages per changed field (never delegated to the AI)
        Map<ChangeRecord, List<String>> usagesByChange = diffReport.changes().stream()
                .collect(Collectors.toMap(
                        change -> change,
                        change -> usageScanner.findUsages(frontendSrcRoot, leafFieldName(change.location()))
                ));

        // Step 2 — build a plain-text description of the diff + usages for the prompt.
        // We pass Day 03's severity AND its own description() in as fact — the AI is not
        // asked to re-derive either, only to add usage context and (optionally) a migration snippet.
        String changesBlock = diffReport.changes().stream()
                .map(change -> """
                        - location: %s
                          severity: %s (already classified — do not change this)
                          category: %s
                          Day 03's own description: %s
                          usages found in frontend: %s
                        """.formatted(
                        change.location(),
                        change.severity(),
                        change.category(),
                        change.description(),
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
            return chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .entity(ChangeReport.class);
        } catch (Exception e) {
            // AI narration is advisory only — a transient upstream failure (rate limit, retry
            // timeout, network hiccup) should never turn into a raw 500 with no useful content.
            // Fall back to Day 03's own deterministic classification + description, which is
            // already decent plain English, plus the real usage-scan data we already computed above.
            List<ChangeExplanation> fallbackChanges = diffReport.changes().stream()
                    .map(change -> new ChangeExplanation(
                            change.location(),
                            change.severity().toString(),
                            change.description(),
                            usagesByChange.getOrDefault(change, List.of())
                    ))
                    .toList();

            return new ChangeReport(
                    "Automated AI narration failed this run (%s) — showing the deterministic classification directly."
                            .formatted(e.getClass().getSimpleName()),
                    fallbackChanges,
                    "",
                    List.of("AI-generated summary and migration suggestion were unavailable this run — see the diff report artifact, or re-run the pipeline.")
            );
        }
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