package com.fitnesscoaching.platform.common.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

import java.net.URI;
import java.time.Duration;

@Component("storageHealthIndicator")
public class StorageHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(StorageHealthIndicator.class);

    private final String endpoint;
    private final String bucket;
    private final String accessKey;
    private final String secretKey;

    public StorageHealthIndicator(
            @Value("${chat.attachments.s3.endpoint:}") String endpoint,
            @Value("${chat.attachments.s3.bucket:}") String bucket,
            @Value("${chat.attachments.s3.access-key:}") String accessKey,
            @Value("${chat.attachments.s3.secret-key:}") String secretKey
    ) {
        this.endpoint = endpoint;
        this.bucket = bucket;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
    }

    @Override
    public Health health() {
        if (!isConfigured()) {
            return Health.unknown()
                    .withDetail("storageType", "s3-compatible")
                    .withDetail("status", "NOT_CONFIGURED")
                    .withDetail("message", "Object storage credentials or endpoint not configured")
                    .build();
        }

        try (S3Client s3Client = createS3Client()) {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            return Health.up()
                    .withDetail("storageType", "s3-compatible")
                    .withDetail("endpoint", endpoint)
                    .withDetail("bucket", bucket)
                    .build();
        } catch (Exception e) {
            log.warn("Storage health probe failed for bucket {}: {}", bucket, e.getMessage());
            return Health.down(e)
                    .withDetail("storageType", "s3-compatible")
                    .withDetail("endpoint", endpoint)
                    .withDetail("bucket", bucket)
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }

    public boolean isConfigured() {
        return endpoint != null && !endpoint.isBlank()
                && bucket != null && !bucket.isBlank()
                && accessKey != null && !accessKey.isBlank()
                && secretKey != null && !secretKey.isBlank();
    }

    private S3Client createS3Client() {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .overrideConfiguration(b -> b.apiCallTimeout(Duration.ofSeconds(3))
                        .apiCallAttemptTimeout(Duration.ofSeconds(2)))
                .forcePathStyle(true)
                .build();
    }
}
