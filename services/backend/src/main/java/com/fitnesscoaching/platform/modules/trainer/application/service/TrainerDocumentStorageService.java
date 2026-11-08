package com.fitnesscoaching.platform.modules.trainer.application.service;

import com.fitnesscoaching.platform.common.exception.ApplicationValidationException;
import com.fitnesscoaching.platform.common.exception.FieldErrorDto;
import com.fitnesscoaching.platform.common.exception.ScannerUnavailableException;
import com.fitnesscoaching.platform.common.exception.StorageUnavailableException;
import com.fitnesscoaching.platform.common.exception.TrainerDocumentNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;

@Service
public class TrainerDocumentStorageService {

    private static final Logger log = LoggerFactory.getLogger(TrainerDocumentStorageService.class);
    public static final int MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB

    private final String endpoint;
    private final String bucket;
    private final String accessKey;
    private final String secretKey;
    private final String clamdHost;
    private final int clamdPort;
    private final Supplier<S3Client> s3ClientSupplier;

    @Autowired
    public TrainerDocumentStorageService(
            @Value("${chat.attachments.s3.endpoint:}") String endpoint,
            @Value("${chat.attachments.s3.bucket:}") String bucket,
            @Value("${chat.attachments.s3.access-key:}") String accessKey,
            @Value("${chat.attachments.s3.secret-key:}") String secretKey,
            @Value("${chat.attachments.clamd.host:}") String clamdHost,
            @Value("${chat.attachments.clamd.port:3310}") int clamdPort
    ) {
        this(endpoint, bucket, accessKey, secretKey, clamdHost, clamdPort, null);
    }

    public TrainerDocumentStorageService(
            String endpoint,
            String bucket,
            String accessKey,
            String secretKey,
            String clamdHost,
            int clamdPort,
            Supplier<S3Client> s3ClientSupplier
    ) {
        this.endpoint = endpoint;
        this.bucket = bucket;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.clamdHost = clamdHost;
        this.clamdPort = clamdPort;
        this.s3ClientSupplier = s3ClientSupplier;
    }

    public String validateAndDetectContentType(byte[] bytes, String declaredType, String filename) {
        if (bytes == null || bytes.length == 0) {
            throw new ApplicationValidationException("Uploaded file cannot be empty",
                    List.of(new FieldErrorDto("file", "NotEmpty", "Uploaded file cannot be empty")));
        }
        if (bytes.length > MAX_FILE_SIZE) {
            throw new ApplicationValidationException("File size exceeds 10MB limit",
                    List.of(new FieldErrorDto("file", "Size", "File size exceeds 10MB limit")));
        }
        if (filename == null || filename.isBlank()) {
            throw new ApplicationValidationException("File name cannot be empty",
                    List.of(new FieldErrorDto("file", "NotBlank", "File name cannot be empty")));
        }
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            throw new ApplicationValidationException("Invalid file name format",
                    List.of(new FieldErrorDto("file", "Pattern", "Invalid file name format")));
        }

        String detected;
        if (starts(bytes, new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff})) {
            detected = "image/jpeg";
        } else if (starts(bytes, new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a})) {
            detected = "image/png";
        } else if (starts(bytes, "%PDF-".getBytes(StandardCharsets.US_ASCII))) {
            detected = "application/pdf";
        } else {
            throw new ApplicationValidationException("Unsupported file type. Only PDF, PNG, and JPEG documents are allowed",
                    List.of(new FieldErrorDto("file", "InvalidType", "Unsupported file type. Only PDF, PNG, and JPEG documents are allowed")));
        }

        return detected;
    }

    public String scanAndVerifyClean(byte[] bytes) {
        if (clamdHost == null || clamdHost.isBlank()) {
            throw new ScannerUnavailableException("Antivirus scanner host is not configured");
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(clamdHost, clamdPort), 3000);
            socket.setSoTimeout(15000);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            out.writeInt(bytes.length);
            out.write(bytes);
            out.writeInt(0);
            out.flush();
            byte[] reply = socket.getInputStream().readNBytes(512);
            String verdict = new String(reply, StandardCharsets.US_ASCII);
            if (verdict.startsWith("stream: OK")) {
                return "CLEAN";
            }
            if (verdict.contains("FOUND")) {
                throw new ApplicationValidationException("Malware detected in uploaded file",
                        List.of(new FieldErrorDto("file", "MALWARE_DETECTED", "Malware detected in uploaded file")));
            }
            log.warn("Unexpected clamd reply: {}", verdict);
            throw new ScannerUnavailableException("Unexpected reply from antivirus scanner: " + verdict);
        } catch (ApplicationValidationException e) {
            throw e;
        } catch (Exception ex) {
            log.warn("Could not connect to clamd host {}:{}: {}", clamdHost, clamdPort, ex.getMessage());
            throw new ScannerUnavailableException("Antivirus scanner is currently unavailable: " + ex.getMessage(), ex);
        }
    }

    public void storeFile(String objectKey, byte[] bytes, String contentType) {
        requireStorageConfigured();
        try (S3Client client = createS3Client()) {
            client.putObject(PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .contentType(contentType)
                    .build(), RequestBody.fromBytes(bytes));
        } catch (Exception e) {
            log.error("S3 upload failed for key {}: {}", objectKey, e.getMessage());
            throw new StorageUnavailableException("Failed to store file in object storage: " + e.getMessage(), e);
        }
    }

    public byte[] loadFile(String objectKey) {
        requireStorageConfigured();
        try (S3Client client = createS3Client()) {
            return client.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .build()).asByteArray();
        } catch (NoSuchKeyException e) {
            throw new TrainerDocumentNotFoundException("Document file not found in storage: " + objectKey);
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                throw new TrainerDocumentNotFoundException("Document file not found in storage: " + objectKey);
            }
            log.error("S3 load failed for key {}: {}", objectKey, e.getMessage());
            throw new StorageUnavailableException("Failed to retrieve file from object storage: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Failed to load object from S3 for key {}: {}", objectKey, e.getMessage());
            throw new StorageUnavailableException("Failed to retrieve file from object storage: " + e.getMessage(), e);
        }
    }

    public void deleteFile(String objectKey) {
        if (!isS3Configured()) {
            return;
        }
        try (S3Client client = createS3Client()) {
            client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .build());
        } catch (Exception e) {
            log.warn("Failed to delete orphaned S3 object {}: {}", objectKey, e.getMessage());
        }
    }

    public String computeSha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception e) {
            return "";
        }
    }

    private void requireStorageConfigured() {
        if (!isS3Configured()) {
            throw new StorageUnavailableException("Object storage is not configured (missing endpoint, bucket, or credentials)");
        }
    }

    public boolean isS3Configured() {
        return endpoint != null && !endpoint.isBlank()
                && bucket != null && !bucket.isBlank()
                && accessKey != null && !accessKey.isBlank()
                && secretKey != null && !secretKey.isBlank();
    }

    private S3Client createS3Client() {
        if (s3ClientSupplier != null) {
            return s3ClientSupplier.get();
        }
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .forcePathStyle(true)
                .build();
    }

    private static boolean starts(byte[] bytes, byte[] prefix) {
        return bytes.length >= prefix.length && Arrays.equals(bytes, 0, prefix.length, prefix, 0, prefix.length);
    }
}
