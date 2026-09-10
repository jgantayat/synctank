package com.synctank.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Day 09 — the ONE place S3 configuration comes from.
 *
 * Until today this record existed alongside a second, separate `s3.*` property tree, and the
 * two disagreed about which was real: S3Config and BucketInitializer read `s3.*` via @Value,
 * while this record (bound to `platform.s3`) was injected only into SpecStore, which read only
 * bucket(). Its endpoint, accessKey and secretKey were never read by anything — rotating them
 * changed nothing, and `platform.s3.bucket` could silently diverge from `s3.bucket`, which
 * would mean BucketInitializer creating one bucket while SpecStore read another.
 *
 * `region` moves in here from the deleted tree. Every consumer now takes this record, so a
 * bucket name or a credential has exactly one origin.
 *
 * accessKey and secretKey are overwritten at startup by SecretsInitializer when
 * platform.secrets.provider=aws — see config/secrets/SecretsInitializer.java.
 */
@ConfigurationProperties(prefix = "platform.s3")
public record S3Props(String endpoint, String bucket, String region,
                      String accessKey, String secretKey) {}