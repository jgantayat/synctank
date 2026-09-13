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
 *
 * Day 10 adds a third kind — REQUIRED_FOR_STATIC_S3. The S3 key pair is required when the spec
 * store is MinIO (platform.s3.auth=static, the default and everything before today), and is not
 * needed at all when the spec store is real S3 behind an ECS task role (platform.s3.auth=iam).
 * Without this, the secret on AWS would have to carry an IAM user's long-lived access keys just
 * to satisfy the table — the exact credential a task role exists to eliminate (F3).
 */
public final class SecretMapping {

    private SecretMapping() {}

    public enum Need { REQUIRED, OPTIONAL, REQUIRED_FOR_STATIC_S3 }

    public record Entry(String jsonKey, String springProperty, Need need, String purpose) {

        /** @param staticS3 true when platform.s3.auth is static (MinIO), false when iam. */
        public boolean requiredWhen(boolean staticS3) {
            return need == Need.REQUIRED || (need == Need.REQUIRED_FOR_STATIC_S3 && staticS3);
        }

        /** True when this key is simply not used under the current S3 mode. */
        public boolean suppliedByRole(boolean staticS3) {
            return need == Need.REQUIRED_FOR_STATIC_S3 && !staticS3;
        }
    }

    public static final List<Entry> ENTRIES = List.of(
            new Entry("s3AccessKey", "platform.s3.access-key", Need.REQUIRED_FOR_STATIC_S3,
                    "Spec store (MinIO / S3)"),
            new Entry("s3SecretKey", "platform.s3.secret-key", Need.REQUIRED_FOR_STATIC_S3,
                    "Spec store (MinIO / S3)"),
            new Entry("dbPassword", "spring.datasource.password", Need.REQUIRED,
                    "Client registry (PostgreSQL)"),
            new Entry("anthropicApiKey", "spring.ai.anthropic.api-key", Need.OPTIONAL,
                    "AI change reports and Contract Agent proposals"),
            new Entry("githubToken", "platform.agent.github-token", Need.OPTIONAL,
                    "Contract Agent pull requests")
    );
}