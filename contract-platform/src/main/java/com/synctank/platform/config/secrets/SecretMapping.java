package com.synctank.platform.config.secrets;

import java.util.List;

/**
 * Day 09 — the single table that says which key in the stored JSON secret feeds which Spring
 * property, and which ones the platform genuinely cannot run without.
 *
 * One file on purpose: a reviewer asking "what credentials does this service hold, and what
 * happens if one is missing?" gets a complete answer here, the same way AgentProperties answers
 * "what can the agent touch?".
 *
 * REQUIRED vs OPTIONAL is a real distinction, not a label:
 *   - required  -> its absence means the platform cannot do its primary job, so startup fails
 *                  rather than silently continuing on the plaintext defaults in application.yaml.
 *   - optional  -> its absence degrades one capability along a path that already exists and is
 *                  already tested. A blank githubToken puts the agent in draft-only mode
 *                  (AgentProperties.hasToken() -> /approve returns 409). A blank anthropicApiKey
 *                  makes the AI call fail and ChangeReportService falls back to the
 *                  deterministic report, exactly as it does on any upstream outage.
 */
public final class SecretMapping {

    private SecretMapping() {}

    public record Entry(String jsonKey, String springProperty, boolean required, String purpose) {}

    public static final List<Entry> ENTRIES = List.of(
            new Entry("s3AccessKey", "platform.s3.access-key", true,
                    "Spec store (MinIO / S3)"),
            new Entry("s3SecretKey", "platform.s3.secret-key", true,
                    "Spec store (MinIO / S3)"),
            new Entry("dbPassword", "spring.datasource.password", true,
                    "Client registry (PostgreSQL)"),
            new Entry("anthropicApiKey", "spring.ai.anthropic.api-key", false,
                    "AI change reports and Contract Agent proposals"),
            new Entry("githubToken", "platform.agent.github-token", false,
                    "Contract Agent pull requests")
    );
}