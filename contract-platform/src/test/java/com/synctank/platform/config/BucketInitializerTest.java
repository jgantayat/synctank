package com.synctank.platform.config;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Day 10 (F4) — BucketInitializer may create the bucket locally, and must not try on AWS.
 *
 * The S3Client is a Mockito mock: the behaviour under test is the branch taken after
 * NoSuchBucketException, not anything S3 does.
 */
class BucketInitializerTest {

    private S3Props props(boolean createIfMissing) {
        return new S3Props("http://localhost:9000", "specs", "us-east-1",
                "k", "s", "static", true, createIfMissing);
    }

    @SuppressWarnings("unchecked")
    private S3Client clientWithNoBucket() {
        S3Client s3 = mock(S3Client.class);
        doThrow(NoSuchBucketException.builder().message("no such bucket").build())
                .when(s3).headBucket(any(Consumer.class));
        return s3;
    }

    @Test
    @SuppressWarnings("unchecked")
    void createsAMissingBucketWhenAllowed() {
        S3Client s3 = clientWithNoBucket();

        new BucketInitializer(s3, props(true)).run(null);

        verify(s3).createBucket(any(Consumer.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void refusesToCreateAMissingBucketWhenNotAllowed() {
        S3Client s3 = clientWithNoBucket();

        assertThatThrownBy(() -> new BucketInitializer(s3, props(false)).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("create-bucket-if-missing=false");

        // The call the task role has no permission for is never attempted.
        verify(s3, never()).createBucket(any(Consumer.class));
    }
}