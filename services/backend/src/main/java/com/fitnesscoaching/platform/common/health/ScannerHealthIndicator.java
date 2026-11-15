package com.fitnesscoaching.platform.common.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

@Component("scannerHealthIndicator")
public class ScannerHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(ScannerHealthIndicator.class);

    private final String host;
    private final int port;

    public ScannerHealthIndicator(
            @Value("${chat.attachments.clamd.host:}") String host,
            @Value("${chat.attachments.clamd.port:3310}") int port
    ) {
        this.host = host;
        this.port = port;
    }

    @Override
    public Health health() {
        if (host == null || host.isBlank()) {
            return Health.unknown()
                    .withDetail("scannerType", "clamd")
                    .withDetail("status", "NOT_CONFIGURED")
                    .withDetail("message", "ClamAV scanner host is not configured")
                    .build();
        }

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 2000);
            socket.setSoTimeout(3000);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.write("zPING\0".getBytes(StandardCharsets.US_ASCII));
            out.flush();

            byte[] reply = socket.getInputStream().readNBytes(16);
            String verdict = new String(reply, StandardCharsets.US_ASCII).trim();
            if (verdict.contains("PONG")) {
                return Health.up()
                        .withDetail("scannerType", "clamd")
                        .withDetail("host", host)
                        .withDetail("port", port)
                        .withDetail("status", "PONG")
                        .build();
            } else {
                return Health.down()
                        .withDetail("scannerType", "clamd")
                        .withDetail("host", host)
                        .withDetail("port", port)
                        .withDetail("unexpectedReply", verdict)
                        .build();
            }
        } catch (Exception e) {
            log.warn("Scanner health probe failed for {}:{}: {}", host, port, e.getMessage());
            return Health.down(e)
                    .withDetail("scannerType", "clamd")
                    .withDetail("host", host)
                    .withDetail("port", port)
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
