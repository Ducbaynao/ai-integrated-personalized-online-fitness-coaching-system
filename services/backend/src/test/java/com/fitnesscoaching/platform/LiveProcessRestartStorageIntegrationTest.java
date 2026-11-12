package com.fitnesscoaching.platform;

import com.fitnesscoaching.platform.modules.trainer.application.service.TrainerDocumentStorageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LiveProcessRestartStorageIntegrationTest {

    private static final String S3_ENDPOINT = "http://localhost:9000";
    private static final String S3_BUCKET = "fitness-media";
    private static final String S3_KEY = "test";
    private static final String S3_SECRET = "test";
    private static final String CLAMAV_HOST = "localhost";
    private static final int CLAMAV_PORT = 3310;

    @Test
    @DisplayName("Live Process Restart Parity: Real backend Spring context starts, stores file, stops, completely restarts, and reloads identical bytes matching SHA-256")
    void liveBackendRestart_fileParity_verified() throws Exception {
        byte[] payload = ("%PDF-1.4\n" +
                "1 0 obj << /Title (Live Backend Process Restart Test) >> endobj\n" +
                "Unique token: " + UUID.randomUUID())
                .getBytes(StandardCharsets.US_ASCII);

        String originalHash = sha256(payload);
        String objectKey = "verification-docs/restart-drill/" + UUID.randomUUID() + "/live-cert.pdf";

        // Phase 1: Boot First Real Spring Context
        ConfigurableApplicationContext firstContext = SpringApplication.run(
                FitnessCoachingBackendApplication.class,
                "--server.port=0",
                "--spring.profiles.active=test",
                "--chat.attachments.s3.endpoint=" + S3_ENDPOINT,
                "--chat.attachments.s3.bucket=" + S3_BUCKET,
                "--chat.attachments.s3.access-key=" + S3_KEY,
                "--chat.attachments.s3.secret-key=" + S3_SECRET,
                "--chat.attachments.clamd.host=" + CLAMAV_HOST,
                "--chat.attachments.clamd.port=" + CLAMAV_PORT,
                "--ai.service.mode=simulated",
                "--ai.embedding.provider=simulated"
        );

        TrainerDocumentStorageService firstStorage = firstContext.getBean(TrainerDocumentStorageService.class);
        firstStorage.storeFile(objectKey, payload, "application/pdf");

        // Verify initial load
        byte[] firstRead = firstStorage.loadFile(objectKey);
        assertThat(sha256(firstRead)).isEqualTo(originalHash);

        // Phase 2: Terminate First Backend Process/Context
        firstContext.close();
        assertThat(firstContext.isActive()).isFalse();

        // Phase 3: Boot Second Real Spring Context (Fresh process simulation)
        ConfigurableApplicationContext restartedContext = SpringApplication.run(
                FitnessCoachingBackendApplication.class,
                "--server.port=0",
                "--spring.profiles.active=test",
                "--chat.attachments.s3.endpoint=" + S3_ENDPOINT,
                "--chat.attachments.s3.bucket=" + S3_BUCKET,
                "--chat.attachments.s3.access-key=" + S3_KEY,
                "--chat.attachments.s3.secret-key=" + S3_SECRET,
                "--chat.attachments.clamd.host=" + CLAMAV_HOST,
                "--chat.attachments.clamd.port=" + CLAMAV_PORT,
                "--ai.service.mode=simulated",
                "--ai.embedding.provider=simulated"
        );

        try {
            TrainerDocumentStorageService restartedStorage = restartedContext.getBean(TrainerDocumentStorageService.class);

            // Phase 4: Reload file through re-initialized backend bean
            byte[] reloadedAfterRestart = restartedStorage.loadFile(objectKey);
            String reloadedHash = sha256(reloadedAfterRestart);

            assertThat(reloadedAfterRestart).isEqualTo(payload);
            assertThat(reloadedHash).isEqualTo(originalHash);

            // Cleanup
            restartedStorage.deleteFile(objectKey);
        } finally {
            restartedContext.close();
        }
    }

    private static String sha256(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(data));
    }
}
