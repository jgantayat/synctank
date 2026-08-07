package com.synctank.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "platform.s3")
public record S3Props(String endpoint, String bucket, String accessKey, String secretKey) {}