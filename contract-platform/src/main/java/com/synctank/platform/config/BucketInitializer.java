package com.synctank.platform.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

/**
 * @Profile("!test") — Day 06 lesson: an ApplicationRunner that makes a real infrastructure
 * call will make `mvn test` depend on live MinIO if it is allowed to run in the test context.
 */
@Profile("!test")
@Component
public class BucketInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BucketInitializer.class);

    private final S3Client s3;
    private final String bucket;

    // Day 09 — reads the bucket from S3Props, the same source SpecStore uses. Previously this
    // read ${s3.bucket} while SpecStore read platform.s3.bucket: two names, one of which
    // honoured S3_BUCKET and one of which did not. Setting S3_BUCKET on a deployed environment
    // would have created one bucket and then read from another.
    public BucketInitializer(S3Client s3, S3Props props) {
        this.s3 = s3;
        this.bucket = props.bucket();
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            s3.headBucket(b -> b.bucket(bucket));
            log.info("Bucket '{}' already exists", bucket);
        } catch (NoSuchBucketException e) {
            s3.createBucket(b -> b.bucket(bucket));
            log.info("Created bucket '{}'", bucket);
        }
    }
}