package com.fitnesscoaching.platform.modules.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Opt-in real clamd test: never binds the unauthenticated TCP port to a fixed host port. */
class ClamdDisposableIntegrationTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_CLAMD_CONTAINER_TEST", matches = "true")
    void cleanBytesPassAndEicarIsRejectedByDisposableClamd() {
        try (GenericContainer<?> clamd = new GenericContainer<>(
                "clamav/clamav@sha256:3bba6dd5ac6663d55b80f86a3126e146612d93b593cc2617188d13f637d91260")
                .withExposedPorts(3310)
                .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(4)))) {
            clamd.start();
            ClamdAttachmentScanner scanner = new ClamdAttachmentScanner(clamd.getHost(), clamd.getMappedPort(3310));
            scanner.requireClean("harmless local test".getBytes(StandardCharsets.US_ASCII));
            String eicar = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";
            assertThatThrownBy(() -> scanner.requireClean(eicar.getBytes(StandardCharsets.US_ASCII)))
                    .isInstanceOf(ChatFailure.class).hasMessage("CHAT_ATTACHMENT_REJECTED");
        }
    }
}
