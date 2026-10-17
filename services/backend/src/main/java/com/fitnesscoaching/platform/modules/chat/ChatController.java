package com.fitnesscoaching.platform.modules.chat;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {
    private final ChatService chat;
    public ChatController(ChatService chat) { this.chat = chat; }

    @GetMapping("/conversations")
    public ChatService.Page<ChatService.Conversation> conversations(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return chat.conversations(actor(jwt), page, size);
    }

    @GetMapping("/conversations/{id}")
    public ChatService.Conversation detail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return chat.detail(actor(jwt), id);
    }

    @GetMapping("/conversations/{id}/messages")
    public ChatService.Page<ChatService.Message> messages(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size) {
        return chat.messages(actor(jwt), id, page, size);
    }

    @PostMapping("/students/{studentId}/messages")
    public ResponseEntity<ChatService.Message> send(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID studentId, @Valid @RequestBody SendRequest body) {
        return ResponseEntity.status(201).body(chat.send(actor(jwt), studentId, body.body(), body.commandKey()));
    }

    @PostMapping("/messages/{messageId}/receipts")
    public ChatService.Message receipt(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID messageId,
            @Valid @RequestBody ReceiptRequest body) {
        return chat.acknowledge(actor(jwt), messageId, body.kind());
    }

    private static UUID actor(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
    public record SendRequest(@NotBlank @Size(max = 4000) String body, @NotNull UUID commandKey) {}
    public record ReceiptRequest(@NotBlank String kind) {}
}
