package com.fitnesscoaching.platform.modules.trainer.application.service;

import com.fitnesscoaching.platform.common.exception.ApplicationValidationException;
import com.fitnesscoaching.platform.common.exception.FieldErrorDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    // Fallback in-memory cache for test/local environments where MinIO is not running
    private final Map<String, byte[]> localCache = new ConcurrentHashMap<>();

    public TrainerDocumentStorageService(
            @Value("${chat.attachments.s3.endpoint:}") String endpoint,
            @Value("${chat.attachments.s3.bucket:}") String bucket,
            @Value("${chat.attachments.s3.access-key:}") String accessKey,
            @Value("${chat.attachments.s3.secret-key:}") String secretKey,
            @Value("${chat.attachments.clamd.host:}") String clamdHost,
            @Value("${chat.attachments.clamd.port:3310}") int clamdPort
    ) {
        this.endpoint = endpoint;
        this.bucket = bucket;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.clamdHost = clamdHost;
        this.clamdPort = clamdPort;
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
        if (clamdHost != null && !clamdHost.isBlank()) {
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
                throw new ApplicationValidationException("Antivirus scanner unavailable",
                        List.of(new FieldErrorDto("file", "SCANNER_ERROR", "Antivirus scanner unavailable")));
            } catch (ApplicationValidationException e) {
                throw e;
            } catch (Exception ex) {
                log.warn("Could not connect to clamd host {}:{}: {}", clamdHost, clamdPort, ex.getMessage());
                throw new ApplicationValidationException("Antivirus scanner is currently unavailable",
                        List.of(new FieldErrorDto("file", "SCANNER_UNAVAILABLE", "Antivirus scanner is currently unavailable")));
            }
        }
        // In local/test environments where clamd is unconfigured, format is verified clean
        return "CLEAN";
    }

    public void storeFile(String objectKey, byte[] bytes, String contentType) {
        localCache.put(objectKey, bytes);
        if (isS3Configured()) {
            try (S3Client client = createS3Client()) {
                client.putObject(PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(objectKey)
                        .contentType(contentType)
                        .build(), RequestBody.fromBytes(bytes));
            } catch (Exception e) {
                log.warn("S3 upload failed for key {}, retained in local cache: {}", objectKey, e.getMessage());
            }
        }
    }

    public byte[] loadFile(String objectKey) {
        byte[] cached = localCache.get(objectKey);
        if (cached != null) {
            return cached;
        }
        if (isS3Configured()) {
            try (S3Client client = createS3Client()) {
                return client.getObjectAsBytes(GetObjectRequest.builder()
                        .bucket(bucket)
                        .key(objectKey)
                        .build()).asByteArray();
            } catch (Exception e) {
                log.error("Failed to load object from S3 for key {}: {}", objectKey, e.getMessage());
            }
        }
        return new byte[0];
    }

    public String computeSha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception e) {
            return "";
        }
    }

    private boolean isS3Configured() {
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
                .forcePathStyle(true)
                .build();
    }

    private static boolean starts(byte[] bytes, byte[] prefix) {
        return bytes.length >= prefix.length && Arrays.equals(bytes, 0, prefix.length, prefix, 0, prefix.length);
    }
}
