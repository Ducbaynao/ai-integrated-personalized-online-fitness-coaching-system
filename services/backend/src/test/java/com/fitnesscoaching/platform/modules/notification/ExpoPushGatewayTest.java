package com.fitnesscoaching.platform.modules.notification;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ExpoPushGatewayTest {
    @Test void ticketAndProviderReceiptRemainDistinctFromDeviceDeliveryAndPayloadIsGeneric() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> sent = new AtomicReference<>();
        server.createContext("/--/api/v2/push/send", exchange -> {
            sent.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"data\":{\"status\":\"ok\",\"id\":\"ticket-1\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/--/api/v2/push/getReceipts", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] response = (request.contains("pending-ticket") ? "{\"data\":{}}"
                    : "{\"data\":{\"ticket-1\":{\"status\":\"ok\"}}}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            ExpoPushGateway gateway = new ExpoPushGateway(new ObjectMapper(),
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/--/api/v2/push", "");
            UUID notice = UUID.randomUUID();
            assertThat(gateway.send("ExpoPushToken[token]", notice))
                    .isEqualTo(new ExpoPushGateway.Result(true, "ticket-1", null));
            assertThat(sent.get()).contains(notice.toString(), "Bạn có cập nhật mới")
                    .doesNotContain("workout", "student", "messageBody", "attachmentName");
            assertThat(gateway.receipt("ticket-1"))
                    .isEqualTo(new ExpoPushGateway.Result(true, "ticket-1", null));
            assertThat(gateway.receipt("pending-ticket"))
                    .isEqualTo(new ExpoPushGateway.Result(false, null, "RECEIPT_UNAVAILABLE"));
        } finally { server.stop(0); }
    }
}
