package com.fitnesscoaching.platform.modules.trainer.application.service;

import com.fitnesscoaching.platform.common.exception.ApplicationValidationException;
import com.fitnesscoaching.platform.common.exception.ScannerUnavailableException;
import com.fitnesscoaching.platform.common.exception.StorageUnavailableException;
import com.fitnesscoaching.platform.common.exception.TrainerDocumentNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrainerDocumentStorageServiceTest {

    private static final byte[] VALID_PDF = "%PDF-1.4 sample content".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] VALID_PNG = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3};
    private static final byte[] VALID_JPEG = new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 1, 2, 3};

    // ==========================================
    // 1. Storage Fail-Closed Tests
    // ==========================================

    @Test
    @DisplayName("storeFile: missing S3 endpoint throws StorageUnavailableException (503)")
    void storeFile_missingEndpoint_throwsStorageUnavailable() {
        TrainerDocumentStorageService storage = new TrainerDocumentStorageService(
                "", "bucket", "key", "secret", "localhost", 3310);
        assertThatThrownBy(() -> storage.storeFile("test-key", VALID_PDF, "application/pdf"))
                .isInstanceOf(StorageUnavailableException.class)
                .hasMessageContaining("not configured");
    }

    @Test
    @DisplayName("storeFile: missing S3 bucket throws StorageUnavailableException (503)")
    void storeFile_missingBucket_throwsStorageUnavailable() {
        TrainerDocumentStorageService storage = new TrainerDocumentStorageService(
                "http://localhost:9000", "", "key", "secret", "localhost", 3310);
        assertThatThrownBy(() -> storage.storeFile("test-key", VALID_PDF, "application/pdf"))
                .isInstanceOf(StorageUnavailableException.class)
                .hasMessageContaining("not configured");
    }

    @Test
    @DisplayName("storeFile: S3 client putObject failure throws StorageUnavailableException (503)")
    void storeFile_s3PutFails_throwsStorageUnavailable() {
        S3Client mockS3 = mock(S3Client.class);
        when(mockS3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().message("Network timeout").statusCode(500).build());

        TrainerDocumentStorageService storage = new TrainerDocumentStorageService(
                "http://localhost:9000", "fitness-media", "key", "secret", "localhost", 3310, () -> mockS3);

        assertThatThrownBy(() -> storage.storeFile("test-key", VALID_PDF, "application/pdf"))
                .isInstanceOf(StorageUnavailableException.class)
                .hasMessageContaining("Failed to store file in object storage");
    }

    @Test
    @DisplayName("storeFile: successful put invokes client without in-memory fallback")
    void storeFile_success_invokesS3Client() {
        S3Client mockS3 = mock(S3Client.class);
        TrainerDocumentStorageService storage = new TrainerDocumentStorageService(
                "http://localhost:9000", "fitness-media", "key", "secret", "localhost", 3310, () -> mockS3);

        storage.storeFile("verification-docs/123/doc.pdf", VALID_PDF, "application/pdf");

        ArgumentCaptor<PutObjectRequest> reqCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(mockS3).putObject(reqCaptor.capture(), any(RequestBody.class));
        assertThat(reqCaptor.getValue().bucket()).isEqualTo("fitness-media");
        assertThat(reqCaptor.getValue().key()).isEqualTo("verification-docs/123/doc.pdf");
        assertThat(reqCaptor.getValue().contentType()).isEqualTo("application/pdf");
    }

    @Test
    @DisplayName("loadFile: missing configuration throws StorageUnavailableException (503)")
    void loadFile_missingConfig_throwsStorageUnavailable() {
        TrainerDocumentStorageService storage = new TrainerDocumentStorageService(
                "", "bucket", "key", "secret", "localhost", 3310);
        assertThatThrownBy(() -> storage.loadFile("test-key"))
                .isInstanceOf(StorageUnavailableException.class)
                .hasMessageContaining("not configured");
    }

    @Test
    @DisplayName("loadFile: NoSuchKeyException throws TrainerDocumentNotFoundException (404)")
    void loadFile_noSuchKey_throwsTrainerDocumentNotFound() {
        S3Client mockS3 = mock(S3Client.class);
        when(mockS3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("Key not found").build());

        TrainerDocumentStorageService storage = new TrainerDocumentStorageService(
                "http://localhost:9000", "fitness-media", "key", "secret", "localhost", 3310, () -> mockS3);

        assertThatThrownBy(() -> storage.loadFile("missing-key"))
                .isInstanceOf(TrainerDocumentNotFoundException.class)
                .hasMessageContaining("Document file not found in storage");
    }

    @Test
    @DisplayName("loadFile: S3Exception 404 throws TrainerDocumentNotFoundException (404)")
    void loadFile_s3NotFound_throwsTrainerDocumentNotFound() {
        S3Client mockS3 = mock(S3Client.class);
        when(mockS3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow(S3Exception.builder().message("Not Found").statusCode(404).build());

        TrainerDocumentStorageService storage = new TrainerDocumentStorageService(
                "http://localhost:9000", "fitness-media", "key", "secret", "localhost", 3310, () -> mockS3);

        assertThatThrownBy(() -> storage.loadFile("missing-key"))
                .isInstanceOf(TrainerDocumentNotFoundException.class)
                .hasMessageContaining("Document file not found in storage");
    }

    @Test
    @DisplayName("loadFile: S3Exception 500 throws StorageUnavailableException (503)")
    void loadFile_s3ServerError_throwsStorageUnavailable() {
        S3Client mockS3 = mock(S3Client.class);
        when(mockS3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow(S3Exception.builder().message("Internal S3 error").statusCode(500).build());

        TrainerDocumentStorageService storage = new TrainerDocumentStorageService(
                "http://localhost:9000", "fitness-media", "key", "secret", "localhost", 3310, () -> mockS3);

        assertThatThrownBy(() -> storage.loadFile("error-key"))
                .isInstanceOf(StorageUnavailableException.class)
                .hasMessageContaining("Failed to retrieve file from object storage");
    }

    @Test
    @DisplayName("loadFile: success returns byte array from S3")
    void loadFile_success_returnsBytes() {
        S3Client mockS3 = mock(S3Client.class);
        GetObjectResponse response = GetObjectResponse.builder().contentLength((long) VALID_PDF.length).build();
        ResponseBytes<GetObjectResponse> responseBytes = ResponseBytes.fromByteArray(response, VALID_PDF);
        when(mockS3.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(responseBytes);

        TrainerDocumentStorageService storage = new TrainerDocumentStorageService(
                "http://localhost:9000", "fitness-media", "key", "secret", "localhost", 3310, () -> mockS3);

        byte[] loaded = storage.loadFile("my-key");
        assertThat(loaded).isEqualTo(VALID_PDF);
    }

    @Test
    @DisplayName("deleteFile: calls S3 deleteObject")
    void deleteFile_callsS3Client() {
        S3Client mockS3 = mock(S3Client.class);
        TrainerDocumentStorageService storage = new TrainerDocumentStorageService(
                "http://localhost:9000", "fitness-media", "key", "secret", "localhost", 3310, () -> mockS3);

        storage.deleteFile("orphan-key");

        ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(mockS3).deleteObject(captor.capture());
        assertThat(captor.getValue().bucket()).isEqualTo("fitness-media");
        assertThat(captor.getValue().key()).isEqualTo("orphan-key");
    }

    // ==========================================
    // 2. Scanner Fail-Closed Tests
    // ==========================================

    @Test
    @DisplayName("scanAndVerifyClean: unconfigured clamdHost throws ScannerUnavailableException (503)")
    void scanAndVerifyClean_missingHost_throwsScannerUnavailable() {
        TrainerDocumentStorageService storage = new TrainerDocumentStorageService(
                "http://localhost:9000", "fitness-media", "key", "secret", "", 3310);

        assertThatThrownBy(() -> storage.scanAndVerifyClean(VALID_PDF))
                .isInstanceOf(ScannerUnavailableException.class)
                .hasMessageContaining("host is not configured");
    }

    @Test
    @DisplayName("scanAndVerifyClean: clamd connection refused / timeout throws ScannerUnavailableException (503)")
    void scanAndVerifyClean_connectionFailure_throwsScannerUnavailable() {
        // Port 1 is reserved/closed on localhost
        TrainerDocumentStorageService storage = new TrainerDocumentStorageService(
                "http://localhost:9000", "fitness-media", "key", "secret", "127.0.0.1", 1);

        assertThatThrownBy(() -> storage.scanAndVerifyClean(VALID_PDF))
                .isInstanceOf(ScannerUnavailableException.class)
                .hasMessageContaining("currently unavailable");
    }

    @Test
    @DisplayName("scanAndVerifyClean: clamd stream: OK returns CLEAN")
    void scanAndVerifyClean_streamOk_returnsClean() throws Exception {
        runClamdMockServer("stream: OK\0", false, (port) -> {
            TrainerDocumentStorageService storage = new TrainerDocumentStorageService(
                    "http://localhost:9000", "fitness-media", "key", "secret", "127.0.0.1", port);
            String result = storage.scanAndVerifyClean(VALID_PDF);
            assertThat(result).isEqualTo("CLEAN");
        });
    }

    @Test
    @DisplayName("scanAndVerifyClean: clamd FOUND returns ApplicationValidationException(MALWARE_DETECTED 400)")
    void scanAndVerifyClean_clamdFound_throwsValidationException() throws Exception {
        runClamdMockServer("stream: Win.Test.EICAR_HDB-1 FOUND\0", true, (port) -> {
            TrainerDocumentStorageService storage = new TrainerDocumentStorageService(
                    "http://localhost:9000", "fitness-media", "key", "secret", "127.0.0.1", port);
            assertThatThrownBy(() -> storage.scanAndVerifyClean(VALID_PDF))
                    .isInstanceOf(ApplicationValidationException.class)
                    .hasMessageContaining("Malware detected in uploaded file");
        });
    }

    @Test
    @DisplayName("scanAndVerifyClean: clamd unexpected reply throws ScannerUnavailableException (503)")
    void scanAndVerifyClean_clamdUnexpectedReply_throwsScannerUnavailable() throws Exception {
        runClamdMockServer("ERROR: memory exhausted\0", false, (port) -> {
            TrainerDocumentStorageService storage = new TrainerDocumentStorageService(
                    "http://localhost:9000", "fitness-media", "key", "secret", "127.0.0.1", port);
            assertThatThrownBy(() -> storage.scanAndVerifyClean(VALID_PDF))
                    .isInstanceOf(ScannerUnavailableException.class)
                    .hasMessageContaining("Unexpected reply from antivirus scanner");
        });
    }

    // ==========================================
    // 3. Validation Tests (Format, Size, Filename)
    // ==========================================

    @Test
    @DisplayName("validateAndDetectContentType: empty bytes throws NotEmpty (400)")
    void validate_emptyBytes_throws400() {
        TrainerDocumentStorageService storage = new TrainerDocumentStorageService("", "", "", "", "", 3310);
        assertThatThrownBy(() -> storage.validateAndDetectContentType(new byte[0], "application/pdf", "doc.pdf"))
                .isInstanceOf(ApplicationValidationException.class)
                .hasMessageContaining("cannot be empty");
    }

    @Test
    @DisplayName("validateAndDetectContentType: size > 10MB throws Size (400)")
    void validate_sizeTooLarge_throws400() {
        TrainerDocumentStorageService storage = new TrainerDocumentStorageService("", "", "", "", "", 3310);
        byte[] large = new byte[10 * 1024 * 1024 + 1];
        System.arraycopy(VALID_PDF, 0, large, 0, VALID_PDF.length);
        assertThatThrownBy(() -> storage.validateAndDetectContentType(large, "application/pdf", "doc.pdf"))
                .isInstanceOf(ApplicationValidationException.class)
                .hasMessageContaining("exceeds 10MB limit");
    }

    @Test
    @DisplayName("validateAndDetectContentType: invalid filename characters throw Pattern (400)")
    void validate_invalidFilename_throws400() {
        TrainerDocumentStorageService storage = new TrainerDocumentStorageService("", "", "", "", "", 3310);
        assertThatThrownBy(() -> storage.validateAndDetectContentType(VALID_PDF, "application/pdf", "../evil.pdf"))
                .isInstanceOf(ApplicationValidationException.class)
                .hasMessageContaining("Invalid file name format");
        assertThatThrownBy(() -> storage.validateAndDetectContentType(VALID_PDF, "application/pdf", "dir/evil.pdf"))
                .isInstanceOf(ApplicationValidationException.class)
                .hasMessageContaining("Invalid file name format");
    }

    @Test
    @DisplayName("validateAndDetectContentType: valid PDF, PNG, and JPEG formats pass")
    void validate_validFormats_pass() {
        TrainerDocumentStorageService storage = new TrainerDocumentStorageService("", "", "", "", "", 3310);
        assertThat(storage.validateAndDetectContentType(VALID_PDF, "application/pdf", "cert.pdf"))
                .isEqualTo("application/pdf");
        assertThat(storage.validateAndDetectContentType(VALID_PNG, "image/png", "cert.png"))
                .isEqualTo("image/png");
        assertThat(storage.validateAndDetectContentType(VALID_JPEG, "image/jpeg", "cert.jpg"))
                .isEqualTo("image/jpeg");
    }

    @Test
    @DisplayName("validateAndDetectContentType: raw text or plain EICAR string rejected as InvalidType (400)")
    void validate_plainEicarText_rejectedAsInvalidTypeBeforeScanning() {
        TrainerDocumentStorageService storage = new TrainerDocumentStorageService("", "", "", "", "", 3310);
        byte[] eicarRaw = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*".getBytes(StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> storage.validateAndDetectContentType(eicarRaw, "text/plain", "eicar.txt"))
                .isInstanceOf(ApplicationValidationException.class)
                .hasMessageContaining("Unsupported file type");
    }

    // ==========================================
    // Helper: Local Mock ClamAV Socket Server
    // ==========================================

    @FunctionalInterface
    interface ServerConsumer {
        void accept(int port) throws Exception;
    }

    private static void runClamdMockServer(String reply, boolean expectFound, ServerConsumer test) throws Exception {
        try (ServerSocket server = new ServerSocket(0);
             ExecutorService workers = Executors.newSingleThreadExecutor()) {
            Future<Boolean> serverFuture = workers.submit(() -> {
                try (var connection = server.accept()) {
                    var in = connection.getInputStream();
                    byte[] command = in.readNBytes(10);
                    if (!"zINSTREAM\0".equals(new String(command, StandardCharsets.US_ASCII))) {
                        throw new AssertionError("Unexpected clamd command");
                    }
                    int length = ByteBuffer.wrap(in.readNBytes(4)).getInt();
                    in.readNBytes(length);
                    int terminator = ByteBuffer.wrap(in.readNBytes(4)).getInt();
                    if (terminator != 0) {
                        throw new AssertionError("Missing stream terminator");
                    }
                    connection.getOutputStream().write(reply.getBytes(StandardCharsets.US_ASCII));
                    connection.getOutputStream().flush();
                }
                return true;
            });

            test.accept(server.getLocalPort());
            serverFuture.get();
        }
    }
}
