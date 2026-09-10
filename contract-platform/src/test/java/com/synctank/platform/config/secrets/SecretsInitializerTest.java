package com.synctank.platform.config.secrets;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day 09 — the mapping from stored JSON to Spring properties, tested without AWS.
 *
 * SecretsInitializer.bind(...) is deliberately a pure static function for exactly this reason:
 * the interesting logic (which key feeds which property, what counts as missing, what a status
 * row may contain) is testable in milliseconds, and the untestable part — one SDK call — is
 * isolated in AwsSecretBundleLoader.
 */
class SecretsInitializerTest {

    private Map<String, String> fullBundle() {
        Map<String, String> bundle = new LinkedHashMap<>();
        bundle.put("s3AccessKey", "minio-access-example");
        bundle.put("s3SecretKey", "minio-secret-example");
        bundle.put("dbPassword", "db-password-example");
        bundle.put("anthropicApiKey", "sk-ant-example");
        bundle.put("githubToken", "github_pat_example");
        return bundle;
    }

    @Test
    void mapsEveryStoredKeyToItsSpringProperty() {
        SecretsInitializer.Binding binding = SecretsInitializer.bind(fullBundle());

        assertThat(binding.properties())
                .containsEntry("platform.s3.access-key", "minio-access-example")
                .containsEntry("platform.s3.secret-key", "minio-secret-example")
                .containsEntry("spring.datasource.password", "db-password-example")
                .containsEntry("spring.ai.anthropic.api-key", "sk-ant-example")
                .containsEntry("platform.agent.github-token", "github_pat_example");

        assertThat(binding.missingRequired()).isEmpty();
    }

    @Test
    void missingOrBlankRequiredKeysAreReportedRatherThanSilentlyFallingBack() {
        Map<String, String> bundle = fullBundle();
        bundle.remove("s3SecretKey");
        bundle.put("dbPassword", "   ");          // whitespace is not a credential

        SecretsInitializer.Binding binding = SecretsInitializer.bind(bundle);

        assertThat(binding.missingRequired())
                .containsExactlyInAnyOrder("s3SecretKey", "dbPassword");
        // Critical: no property is published for a missing required key, so the YAML default
        // is never silently reinstated. loadFromAws() turns this list into a startup failure.
        assertThat(binding.properties())
                .doesNotContainKey("platform.s3.secret-key")
                .doesNotContainKey("spring.datasource.password");
    }

    @Test
    void anAbsentOptionalKeyIsAcceptedAndLeavesThatCapabilityUnconfigured() {
        Map<String, String> bundle = fullBundle();
        bundle.remove("githubToken");

        SecretsInitializer.Binding binding = SecretsInitializer.bind(bundle);

        assertThat(binding.missingRequired()).isEmpty();
        // No property published -> platform.agent.github-token keeps its blank YAML default ->
        // AgentProperties.hasToken() is false -> the agent is in draft-only mode and /approve
        // returns 409. Day 07's behaviour, reached through Day 09's mechanism.
        assertThat(binding.properties()).doesNotContainKey("platform.agent.github-token");

        SecretsStatus.KeyStatus token = binding.keys().stream()
                .filter(k -> k.property().equals("platform.agent.github-token"))
                .findFirst().orElseThrow();
        assertThat(token.present()).isFalse();
        assertThat(token.source()).isEqualTo("absent");
        assertThat(token.fingerprint()).isNull();
    }

    @Test
    void statusRowsNeverCarryTheCredentialItself() {
        SecretsInitializer.Binding binding = SecretsInitializer.bind(fullBundle());

        assertThat(binding.keys()).allSatisfy(key -> {
            assertThat(key.toString()).doesNotContain(
                    "minio-access-example", "minio-secret-example", "db-password-example",
                    "sk-ant-example", "github_pat_example");
            assertThat(key.fingerprint()).hasSize(8);
            assertThat(key.source()).isEqualTo("aws");
        });
    }
}