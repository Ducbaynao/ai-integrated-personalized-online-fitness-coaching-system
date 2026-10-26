package com.fitnesscoaching.platform.modules.chat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;

/** No public URL or presigned URL is returned: all reads pass through Chat authority. */
@Component
public class ChatObjectStorage {
    private final String endpoint;
    private final String bucket;
    private final String accessKey;
    private final String secretKey;

    public ChatObjectStorage(@Value("${chat.attachments.s3.endpoint:}") String endpoint,
                             @Value("${chat.attachments.s3.bucket:}") String bucket,
                             @Value("${chat.attachments.s3.access-key:}") String accessKey,
                             @Value("${chat.attachments.s3.secret-key:}") String secretKey) {
        this.endpoint = endpoint; this.bucket = bucket;
        this.accessKey = accessKey; this.secretKey = secretKey;
    }

    public String bucket() { return bucket; }

    public void put(String key, byte[] bytes, String contentType) {
        requireConfigured();
        try (S3Client client = client()) {
            client.putObject(PutObjectRequest.builder().bucket(bucket).key(key)
                    .contentType(contentType).build(), RequestBody.fromBytes(bytes));
        } catch (Exception unavailable) {
            throw new ChatFailure("CHAT_ATTACHMENT_STORAGE_UNAVAILABLE", 503);
        }
    }

    public byte[] get(String key) {
        requireConfigured();
        try (S3Client client = client()) {
            return client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
        } catch (Exception unavailable) {
            throw new ChatFailure("CHAT_ATTACHMENT_STORAGE_UNAVAILABLE", 503);
        }
    }

    public void delete(String key) {
        if (endpoint.isBlank()) return;
        try (S3Client client = client()) {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (Exception ignored) {
            // Object orphan is inaccessible without a committed media row; operational reconciliation follows.
        }
    }

    private void requireConfigured() {
        if (endpoint.isBlank() || bucket.isBlank() || accessKey.isBlank() || secretKey.isBlank())
            throw new ChatFailure("CHAT_ATTACHMENT_STORAGE_UNAVAILABLE", 503);
    }

    private S3Client client() {
        return S3Client.builder().endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .build();
    }
}
