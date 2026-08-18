package com.synctank.platform.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
@Profile("!test")
@Component
public class BucketInitializer implements ApplicationRunner {

    private final S3Client s3;
    private final String bucket;

    public BucketInitializer(S3Client s3, @Value("${s3.bucket}") String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            s3.headBucket(b -> b.bucket(bucket));
            System.out.println("Bucket '" + bucket + "' already exists");
        } catch (NoSuchBucketException e) {
            s3.createBucket(b -> b.bucket(bucket));
            System.out.println("Created bucket '" + bucket + "'");
        }
    }
}