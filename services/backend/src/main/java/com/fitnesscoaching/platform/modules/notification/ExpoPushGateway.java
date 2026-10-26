package com.fitnesscoaching.platform.modules.notification;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

/** Expo ticket and receipt are provider states, never proof of device delivery. */
@Component
public class ExpoPushGateway {
    private static final Logger log = LoggerFactory.getLogger(ExpoPushGateway.class);
    private final ObjectMapper json;
    private final URI endpoint;
    private final String accessToken;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public ExpoPushGateway(ObjectMapper json,
            @Value("${push.expo.endpoint:https://exp.host/--/api/v2/push}") String endpoint,
            @Value("${push.expo.access-token:}") String accessToken) {
        this.json = json; this.endpoint = URI.create(endpoint.replaceAll("/+$", ""));
        this.accessToken = accessToken;
    }

    public record Result(boolean accepted, String id, String error) {}

    public Result send(String token, UUID notificationId) {
        ObjectNode body = json.createObjectNode();
        body.put("to", token);
        body.put("title", "Thông báo mới");
        body.put("body", "Bạn có cập nhật mới");
        body.putObject("data").put("notificationId", notificationId.toString());
        return invoke("/send", body);
    }

    public Result receipt(String ticketId) {
        ObjectNode body = json.createObjectNode();
        body.putArray("ids").add(ticketId);
        try {
            JsonNode response = post("/getReceipts", body);
            JsonNode receipt = response.path("data").path(ticketId);
            // Expo omits IDs whose receipts are not available yet. Keep the ticket pending.
            if (!receipt.isObject() || !receipt.hasNonNull("status"))
                return new Result(false, null, "RECEIPT_UNAVAILABLE");
            if ("ok".equals(receipt.path("status").asText())) return new Result(true, ticketId, null);
            return new Result(false, null, receipt.path("details").path("error").asText("RECEIPT_REJECTED"));
        } catch (Exception error) {
            log.warn("Expo receipt unavailable: {}", error.getClass().getSimpleName());
            return new Result(false, null, "RECEIPT_UNAVAILABLE");
        }
    }

    private Result invoke(String path, ObjectNode body) {
        try {
            JsonNode ticket = post(path, body).path("data");
            if ("ok".equals(ticket.path("status").asText()) && ticket.hasNonNull("id"))
                return new Result(true, ticket.path("id").asText(), null);
            return new Result(false, null, ticket.path("details").path("error").asText("TICKET_REJECTED"));
        } catch (Exception error) {
            // Unknown network outcome is recorded FAILED and is not blindly retried.
            log.warn("Expo ticket outcome unknown: {}", error.getClass().getSimpleName());
            return new Result(false, null, "TICKET_OUTCOME_UNKNOWN");
        }
    }

    private JsonNode post(String path, ObjectNode body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint.resolve(endpoint.getPath() + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (!accessToken.isBlank()) request.header("Authorization", "Bearer " + accessToken);
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("EXPO_HTTP_" + response.statusCode());
        return json.readTree(response.body());
    }
}
