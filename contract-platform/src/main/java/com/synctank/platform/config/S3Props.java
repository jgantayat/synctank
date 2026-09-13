package com.synctank.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Locale;

/**
 * Day 09 — the ONE place S3 configuration comes from.
 *
 * Until Day 09 this record existed alongside a second, separate `s3.*` property tree, and the
 * two disagreed about which was real. Every consumer now takes this record, so a bucket name or
 * a credential has exactly one origin.
 *
 * accessKey and secretKey are overwritten at startup by SecretsInitializer when
 * platform.secrets.provider=aws — see config/secrets/SecretsInitializer.java.
 *
 * Day 10 adds three switches, all defaulting to exactly what Days 01–09 did:
 *
 *   auth                   static (default) — MinIO: an access-key pair, supplied by Secrets
 *                                             Manager or env vars.
 *                          iam              — real S3 behind an ECS task role: the SDK's default
 *                                             credential chain, NO key pair anywhere. This is the
 *                                             switch that lets the IAM policy in infra/aws/iam/
 *                                             actually be the thing that grants access (F3).
 *   pathStyle              true (default) for MinIO; false for real S3 (virtual-hosted URLs).
 *   createBucketIfMissing  true (default) locally, where BucketInitializer creating `specs` in
 *                          a fresh MinIO is a convenience. false on AWS, where the bucket is
 *                          provisioned infrastructure and s3:CreateBucket is deliberately NOT in
 *                          the task role's policy (F4).
 *
 * An endpoint that is blank means "real AWS" — no override is applied (see S3Config).
 */
@ConfigurationProperties(prefix = "platform.s3")
public record S3Props(String endpoint,
                      String bucket,
                      String region,
                      String accessKey,
                      String secretKey,
                      @DefaultValue("static") String auth,
                      @DefaultValue("true") boolean pathStyle,
                      @DefaultValue("true") boolean createBucketIfMissing) {

    public enum AuthMode { STATIC, IAM }

    /** Parsed once per call; an unknown value is a configuration error, not a silent default. */
    public AuthMode authMode() {
        String value = auth == null ? "static" : auth.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "", "static" -> AuthMode.STATIC;
            case "iam" -> AuthMode.IAM;
            default -> throw new IllegalStateException(
                    "Unknown platform.s3.auth '" + auth + "'. Valid values: static, iam.");
        };
    }

    public boolean hasEndpointOverride() {
        return endpoint != null && !endpoint.isBlank();
    }
}