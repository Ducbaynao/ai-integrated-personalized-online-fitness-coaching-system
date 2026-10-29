package com.fitnesscoaching.platform.modules.chat;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.PutBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.VersioningConfiguration;

import java.net.URI;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatObjectStorageTest {
    @Test void privateDisposableObjectRoundTrip() {
        try (GenericContainer<?> s3 = new GenericContainer<>(
                "adobe/s3mock@sha256:ab01a6946750f451ca215a47e91030695b260e4003b8a5a6201d25029b8fca92")
                .withExposedPorts(9090)
                .waitingFor(Wait.forHttp("/favicon.ico").forPort(9090))) {
            s3.start();
            String endpoint = "http://" + s3.getHost() + ":" + s3.getMappedPort(9090);
            String bucket = "private-chat-test";
            try (S3Client client = S3Client.builder().endpointOverride(URI.create(endpoint))
                    .region(Region.US_EAST_1).forcePathStyle(true)
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create("test-access", "test-secret-123"))).build()) {
                client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            }
            ChatObjectStorage storage = new ChatObjectStorage(endpoint, bucket, "test-access", "test-secret-123", true);
            String key = "chat/test/" + UUID.randomUUID();
            byte[] bytes = new byte[] {1, 2, 3};
            storage.put(key, bytes, "image/png");
            assertThat(storage.get(key)).containsExactly(bytes);
            storage.delete(key);
            assertThatThrownBy(() -> storage.get(key))
                    .isInstanceOf(ChatFailure.class)
                    .hasMessage("CHAT_ATTACHMENT_STORAGE_UNAVAILABLE");
            String erasureKey = "chat/erasure/" + UUID.randomUUID();
            storage.put(erasureKey, bytes, "image/png");
            ChatObjectStorage unverified = new ChatObjectStorage(endpoint, bucket,
                    "test-access", "test-secret-123", false);
            assertThatThrownBy(() -> unverified.deleteAndVerify(bucket, erasureKey))
                    .isInstanceOf(ChatFailure.class)
                    .hasMessage("CHAT_ATTACHMENT_ERASURE_TOPOLOGY_UNVERIFIED");
            assertThat(storage.get(erasureKey)).containsExactly(bytes);
            storage.deleteAndVerify(bucket, erasureKey);
            storage.deleteAndVerify(bucket, erasureKey); // missing object is an idempotent verified result
            assertThatThrownBy(() -> storage.get(erasureKey)).isInstanceOf(ChatFailure.class);
            assertThatThrownBy(() -> storage.deleteAndVerify("another-bucket", erasureKey))
                    .isInstanceOf(ChatFailure.class);
            try (S3Client client = S3Client.builder().endpointOverride(URI.create(endpoint))
                    .region(Region.US_EAST_1).forcePathStyle(true)
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create("test-access", "test-secret-123"))).build()) {
                client.putBucketVersioning(PutBucketVersioningRequest.builder().bucket(bucket)
                        .versioningConfiguration(VersioningConfiguration.builder()
                                .status(BucketVersioningStatus.ENABLED).build()).build());
                String versionedKey = "chat/versioned/" + UUID.randomUUID();
                storage.put(versionedKey, bytes, "image/png");
                storage.put(versionedKey, new byte[] {4, 5, 6}, "image/png");
                assertThatThrownBy(() -> storage.deleteAndVerify(bucket, versionedKey))
                        .isInstanceOf(ChatFailure.class)
                        .hasMessage("CHAT_ATTACHMENT_ERASURE_TOPOLOGY_UNVERIFIED");
                assertThat(client.listObjectVersions(ListObjectVersionsRequest.builder()
                        .bucket(bucket).prefix(versionedKey).build()).versions()).hasSize(2);
            }
        }
    }
}
