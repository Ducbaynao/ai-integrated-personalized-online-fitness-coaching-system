package com.fitnesscoaching.platform.modules.trainer.application.service;

import com.fitnesscoaching.platform.common.exception.ApplicationValidationException;
import com.fitnesscoaching.platform.common.exception.ScannerUnavailableException;
import com.fitnesscoaching.platform.common.exception.StorageUnavailableException;
import com.fitnesscoaching.platform.common.exception.TrainerDocumentNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LiveStorageAndScannerHardeningIntegrationTest {

    private static final String S3_ENDPOINT = "http://localhost:9000";
    private static final String S3_BUCKET = "fitness-media";
    private static final String S3_ACCESS_KEY = "test";
    private static final String S3_SECRET_KEY = "test";
    private static final String CLAMAV_HOST = "localhost";
    private static final int CLAMAV_PORT = 3310;

    private static final byte[] EICAR_TEST_VIRUS =
            "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*"
                    .getBytes(StandardCharsets.US_ASCII);

    private TrainerDocumentStorageService storageService;
    private String testObjectKey;

    @BeforeEach
    void setUp() {
        storageService = new TrainerDocumentStorageService(
                S3_ENDPOINT,
                S3_BUCKET,
                S3_ACCESS_KEY,
                S3_SECRET_KEY,
                CLAMAV_HOST,
                CLAMAV_PORT
        );
        testObjectKey = "test-verification/docs/" + UUID.randomUUID() + "/cert.pdf";
    }

    @AfterEach
    void tearDown() {
        if (testObjectKey != null) {
            try {
                storageService.deleteFile(testObjectKey);
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    @DisplayName("Live ClamAV: Clean PDF document passes scan successfully")
    void liveClamav_cleanFile_returnsClean() {
        byte[] cleanPdf = "%PDF-1.4 Mock Trainer Certificate for testing hash integrity".getBytes(StandardCharsets.US_ASCII);

        String verdict = storageService.scanAndVerifyClean(cleanPdf);

        assertThat(verdict).isEqualTo("CLEAN");
    }

    @Test
    @DisplayName("Live ClamAV: EICAR test signature is detected and throws ApplicationValidationException (MALWARE_DETECTED)")
    void liveClamav_eicarSignature_throwsMalwareDetected() {
        assertThatThrownBy(() -> storageService.scanAndVerifyClean(EICAR_TEST_VIRUS))
                .isInstanceOf(ApplicationValidationException.class)
                .satisfies(ex -> {
                    ApplicationValidationException ave = (ApplicationValidationException) ex;
                    assertThat(ave.getMessage()).contains("Malware detected");
                    assertThat(ave.getFieldErrors()).anyMatch(fe -> "MALWARE_DETECTED".equals(fe.code()));
                });
    }

    @Test
    @DisplayName("Scanner Fail-Closed: Unreachable ClamAV host throws ScannerUnavailableException (503)")
    void scannerFailClosed_unreachableHost_throwsScannerUnavailableException() {
        TrainerDocumentStorageService brokenScannerStorage = new TrainerDocumentStorageService(
                S3_ENDPOINT,
                S3_BUCKET,
                S3_ACCESS_KEY,
                S3_SECRET_KEY,
                "127.0.0.1",
                3319 // invalid / closed port
        );

        byte[] cleanPdf = "%PDF-1.4 sample content".getBytes(StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> brokenScannerStorage.scanAndVerifyClean(cleanPdf))
                .isInstanceOf(ScannerUnavailableException.class)
                .hasMessageContaining("Antivirus scanner is currently unavailable");
    }

    @Test
    @DisplayName("Live S3: Upload and reload PDF document with SHA-256 hash match")
    void liveS3_uploadAndReload_hashMatches() throws Exception {
        byte[] originalContent = ("%PDF-1.4\n" +
                "1 0 obj\n" +
                "<< /Type /Catalog /Pages 2 0 R >>\n" +
                "endobj\n" +
                "trailer << /Root 1 0 R >>\n" +
                "%%EOF\n" +
                "Unique token: " + UUID.randomUUID())
                .getBytes(StandardCharsets.US_ASCII);

        String originalHash = sha256(originalContent);

        // Store file in live S3
        storageService.storeFile(testObjectKey, originalContent, "application/pdf");

        // Reload file from live S3
        byte[] reloadedContent = storageService.loadFile(testObjectKey);
        String reloadedHash = sha256(reloadedContent);

        assertThat(reloadedContent).isEqualTo(originalContent);
        assertThat(reloadedHash).isEqualTo(originalHash);
    }

    @Test
    @DisplayName("Live S3: Durability across backend restart simulation maintains byte and hash parity")
    void liveS3_durabilityAcrossBackendRestart_maintainsIntegrity() throws Exception {
        byte[] pngHeader = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        byte[] payload = UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8);
        byte[] originalPng = new byte[pngHeader.length + payload.length];
        System.arraycopy(pngHeader, 0, originalPng, 0, pngHeader.length);
        System.arraycopy(payload, 0, originalPng, pngHeader.length, payload.length);

        String originalHash = sha256(originalPng);
        testObjectKey = "test-verification/images/" + UUID.randomUUID() + "/progress.png";

        // Step 1: Upload with original storage service instance
        storageService.storeFile(testObjectKey, originalPng, "image/png");

        // Step 2: Simulate backend restart by creating a completely fresh service instance
        TrainerDocumentStorageService restartedStorageService = new TrainerDocumentStorageService(
                S3_ENDPOINT,
                S3_BUCKET,
                S3_ACCESS_KEY,
                S3_SECRET_KEY,
                CLAMAV_HOST,
                CLAMAV_PORT
        );

        // Step 3: Load from the new service instance
        byte[] reloadedAfterRestart = restartedStorageService.loadFile(testObjectKey);
        String reloadedHash = sha256(reloadedAfterRestart);

        assertThat(reloadedAfterRestart).isEqualTo(originalPng);
        assertThat(reloadedHash).isEqualTo(originalHash);
    }

    @Test
    @DisplayName("Storage Fail-Closed: Unreachable S3 endpoint throws StorageUnavailableException (503)")
    void storageFailClosed_unreachableEndpoint_throwsStorageUnavailableException() {
        TrainerDocumentStorageService brokenS3Storage = new TrainerDocumentStorageService(
                "http://localhost:9999", // Unreachable S3 endpoint
                S3_BUCKET,
                S3_ACCESS_KEY,
                S3_SECRET_KEY,
                CLAMAV_HOST,
                CLAMAV_PORT
        );

        byte[] cleanPdf = "%PDF-1.4 sample content".getBytes(StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> brokenS3Storage.storeFile("test-key", cleanPdf, "application/pdf"))
                .isInstanceOf(StorageUnavailableException.class)
                .hasMessageContaining("Failed to store file in object storage");

        assertThatThrownBy(() -> brokenS3Storage.loadFile("test-key"))
                .isInstanceOf(StorageUnavailableException.class)
                .hasMessageContaining("Failed to retrieve file from object storage");
    }

    @Test
    @DisplayName("Storage: Requesting non-existent key throws TrainerDocumentNotFoundException (404)")
    void storage_nonExistentKey_throwsNotFoundException() {
        String nonExistentKey = "verification-docs/non-existent-" + UUID.randomUUID() + ".pdf";

        assertThatThrownBy(() -> storageService.loadFile(nonExistentKey))
                .isInstanceOf(TrainerDocumentNotFoundException.class)
                .hasMessageContaining("Document file not found in storage");
    }

    private static String sha256(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(data));
    }
}
