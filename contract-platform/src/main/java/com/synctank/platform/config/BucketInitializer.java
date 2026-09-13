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
    private final boolean createIfMissing;

    // Day 09 — reads the bucket from S3Props, the same source SpecStore uses.
    // Day 10 — and whether it is allowed to create it (F4).
    public BucketInitializer(S3Client s3, S3Props props) {
        this.s3 = s3;
        this.bucket = props.bucket();
        this.createIfMissing = props.createBucketIfMissing();
    }

    /**
     * headBucket needs s3:ListBucket on the bucket — which the task role has, because SpecStore
     * needs it too (see the policy's own notes). createBucket would need s3:CreateBucket, which
     * the task role deliberately does NOT have: on AWS the bucket is infrastructure, created once
     * by whoever provisions the account, not by an application on every boot.
     *
     * So with createIfMissing=false a missing bucket stops the platform at startup with a message
     * that says what to do — rather than an AccessDenied from a call it should never have made.
     */
    @Override
    public void run(ApplicationArguments args) {
        try {
            s3.headBucket(b -> b.bucket(bucket));
            log.info("Bucket '{}' already exists", bucket);
        } catch (NoSuchBucketException e) {
            if (!createIfMissing) {
                throw new IllegalStateException("Spec bucket '" + bucket + "' does not exist and "
                        + "platform.s3.create-bucket-if-missing=false. Provision the bucket first "
                        + "(infra/aws/README.md) — the platform's IAM role cannot and should not "
                        + "create it.", e);
            }
            s3.createBucket(b -> b.bucket(bucket));
            log.info("Created bucket '{}'", bucket);
        }
    }
}