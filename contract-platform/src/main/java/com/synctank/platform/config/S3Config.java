package com.synctank.platform.config;

import java.net.URI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

@Configuration
public class S3Config {

    /**
     * Day 09 — takes S3Props rather than loose @Value lookups against a second property tree.
     *
     * Day 10 — the three hard-wired MinIO assumptions become configuration (F3):
     *
     *   credentials   STATIC keeps Day 09 exactly: an access-key pair from Secrets Manager or
     *                 env vars, because MinIO has no notion of an IAM role. IAM hands the client
     *                 the SDK's default chain, which on ECS resolves the task role — so the policy
     *                 in infra/aws/iam/contract-platform-task-role-policy.json is what grants
     *                 access, and no S3 key exists anywhere to leak or rotate.
     *   endpoint      applied only when set. Blank means real S3's regional endpoint.
     *   path style    MinIO needs it (Day-01 lesson); real S3 prefers virtual-hosted URLs.
     *
     * Every default reproduces Day 09's behaviour, so CI and local runs are unaffected.
     */
    @Bean
    public S3Client s3Client(S3Props props) {
        S3ClientBuilder builder = S3Client.builder()
                .credentialsProvider(credentialsFor(props))
                .region(Region.of(props.region()))
                .forcePathStyle(props.pathStyle());

        if (props.hasEndpointOverride()) {
            builder = builder.endpointOverride(URI.create(props.endpoint()));
        }
        return builder.build();
    }

    /**
     * Pure function, unit-tested in S3ConfigTest — which credentials a given configuration gets
     * is the security-relevant decision here, so it is testable without building a client.
     *
     * STATIC with a blank key is refused rather than passed through: the SDK would accept an
     * empty key pair and fail later, per request, with a 403 that looks like an IAM problem.
     * Never echoes a value — the message names the properties, not their contents.
     */
    static AwsCredentialsProvider credentialsFor(S3Props props) {
        return switch (props.authMode()) {
            case IAM -> DefaultCredentialsProvider.create();
            case STATIC -> {
                if (isBlank(props.accessKey()) || isBlank(props.secretKey())) {
                    throw new IllegalStateException("platform.s3.auth=static requires "
                            + "platform.s3.access-key and platform.s3.secret-key. Supply them via "
                            + "Secrets Manager (s3AccessKey / s3SecretKey) or S3_ACCESS_KEY / "
                            + "S3_SECRET_KEY, or set S3_AUTH=iam to use the task role.");
                }
                yield StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.accessKey(), props.secretKey()));
            }
        };
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}