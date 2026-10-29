package com.fitnesscoaching.platform.modules.chat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
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
    private final boolean erasureTopologyAttested;

    public ChatObjectStorage(@Value("${chat.attachments.s3.endpoint:}") String endpoint,
                             @Value("${chat.attachments.s3.bucket:}") String bucket,
                             @Value("${chat.attachments.s3.access-key:}") String accessKey,
                             @Value("${chat.attachments.s3.secret-key:}") String secretKey,
                             @Value("${chat.attachments.s3.erasure-topology-attested:false}")
                             boolean erasureTopologyAttested) {
        this.endpoint = endpoint; this.bucket = bucket;
        this.accessKey = accessKey; this.secretKey = secretKey;
        this.erasureTopologyAttested = erasureTopologyAttested;
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

    /** Erasure path: unlike rollback cleanup, failures cannot be swallowed. */
    public void deleteAndVerify(String bucketName, String key) {
        requireConfigured();
        if (!bucket.equals(bucketName)) throw new ChatFailure("CHAT_ATTACHMENT_STORAGE_UNAVAILABLE", 503);
        try (S3Client client = client()) {
            // A plain DELETE in a versioned/suspended bucket inserts a delete
            // marker; HEAD 404 would not prove the old bytes were removed.
            var versioning = client.getBucketVersioning(GetBucketVersioningRequest.builder()
                    .bucket(bucket).build()).status();
            if (versioning != null || !erasureTopologyAttested)
                throw new ChatFailure("CHAT_ATTACHMENT_ERASURE_TOPOLOGY_UNVERIFIED", 503);
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
            try {
                client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
                throw new ChatFailure("CHAT_ATTACHMENT_STORAGE_UNAVAILABLE", 503);
            } catch (S3Exception missing) {
                if (missing.statusCode() != 404) throw missing;
            }
        } catch (ChatFailure failure) {
            throw failure;
        } catch (Exception unavailable) {
            throw new ChatFailure("CHAT_ATTACHMENT_STORAGE_UNAVAILABLE", 503);
        }
        // Current key is absent in a non-versioned bucket with an independently
        // attested replica topology. The attestation is an external release gate.
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
