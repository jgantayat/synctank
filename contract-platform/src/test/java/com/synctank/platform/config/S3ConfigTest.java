package com.synctank.platform.config;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Day 10 (F3) — which credentials the spec-store client gets is the security-relevant decision
 * in S3Config, so it is tested directly. No client is built and no network call is made.
 */
class S3ConfigTest {

    private static final String ACCESS = "EXAMPLE-ACCESS-VALUE";
    private static final String SECRET = "EXAMPLE-SECRET-VALUE";

    private S3Props props(String auth, String accessKey, String secretKey) {
        return new S3Props("http://localhost:9000", "specs", "us-east-1",
                accessKey, secretKey, auth, true, true);
    }

    @Test
    void staticModeUsesTheConfiguredKeyPair() {
        AwsCredentialsProvider provider = S3Config.credentialsFor(props("static", ACCESS, SECRET));

        assertThat(provider).isInstanceOf(StaticCredentialsProvider.class);
        assertThat(provider.resolveCredentials().accessKeyId()).isEqualTo(ACCESS);
    }

    @Test
    void iamModeUsesTheDefaultChainAndNeedsNoKeyPair() {
        AwsCredentialsProvider provider = S3Config.credentialsFor(props("iam", null, null));

        // On ECS the default chain resolves the task role — that is the whole point of the mode.
        // (resolveCredentials() is deliberately NOT called: there is no role on a test machine.)
        assertThat(provider).isInstanceOf(DefaultCredentialsProvider.class);
    }

    @Test
    void staticModeWithABlankKeyFailsAtStartupWithoutEchoingAnyValue() {
        assertThatThrownBy(() -> S3Config.credentialsFor(props("static", ACCESS, "  ")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("S3_AUTH=iam")
                .hasMessageNotContaining(ACCESS);
    }

    @Test
    void anUnknownAuthModeIsAConfigurationErrorNotASilentDefault() {
        assertThatThrownBy(() -> S3Config.credentialsFor(props("role", ACCESS, SECRET)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Valid values: static, iam");
    }
}