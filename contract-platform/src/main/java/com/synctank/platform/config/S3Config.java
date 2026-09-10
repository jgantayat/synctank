package com.synctank.platform.config;

import java.net.URI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration
public class S3Config {

    /**
     * Day 09 — takes S3Props rather than four loose @Value lookups against a second property
     * tree. Behaviour is identical when the values agree; the point is that they can no longer
     * disagree.
     *
     * Still StaticCredentialsProvider rather than DefaultCredentialsProvider: this client talks
     * to MinIO, which has its own access key pair and no notion of an IAM role. Where those two
     * strings COME from is what changed today — Secrets Manager rather than a YAML literal.
     * Day 10 revisits this if the spec store moves to real S3 behind a task role.
     */
    @Bean
    public S3Client s3Client(S3Props props) {
        return S3Client.builder()
                .endpointOverride(URI.create(props.endpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.accessKey(), props.secretKey())))
                .region(Region.of(props.region()))
                .forcePathStyle(true)   // Day-01 lesson: mandatory for MinIO
                .build();
    }
}