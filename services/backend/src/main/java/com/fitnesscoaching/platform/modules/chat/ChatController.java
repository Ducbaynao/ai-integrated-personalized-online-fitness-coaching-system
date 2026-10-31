package com.fitnesscoaching.platform.modules.chat;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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

    @PostMapping(value = "/students/{studentId}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ChatService.Message> sendAttachment(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID studentId, @RequestPart("file") MultipartFile file,
            @RequestParam("commandKey") UUID commandKey) throws java.io.IOException {
        if (file.getSize() > ChatAttachmentPolicy.MAX_BYTES)
            throw new ChatFailure("CHAT_ATTACHMENT_SIZE_INVALID", 400);
        return ResponseEntity.status(201).body(chat.sendAttachment(actor(jwt), studentId,
                file.getOriginalFilename(), file.getContentType(), file.getBytes(), commandKey));
    }

    @PostMapping("/students/{studentId}/linked-resources")
    public ResponseEntity<ChatService.Message> sendLinked(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID studentId, @Valid @RequestBody LinkedRequest input) {
        return ResponseEntity.status(201).body(chat.sendLinked(actor(jwt), studentId,
                input.type(), input.resourceId(), input.parentId(), input.commandKey()));
    }

    @GetMapping("/messages/{messageId}/linked-resource")
    public ResponseEntity<ChatService.LinkedResource> openLinked(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID messageId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(chat.openLinked(actor(jwt), messageId));
    }

    @GetMapping("/attachments/{attachmentId}/content")
    public ResponseEntity<byte[]> download(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID attachmentId) {
        ChatService.AttachmentContent file = chat.download(actor(jwt), attachmentId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.filename()).build().toString())
                .contentType(MediaType.parseMediaType(file.contentType())).body(file.bytes());
    }

    @PostMapping("/messages/{messageId}/receipts")
    public ChatService.Message receipt(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID messageId,
            @Valid @RequestBody ReceiptRequest body) {
        return chat.acknowledge(actor(jwt), messageId, body.kind());
    }

    private static UUID actor(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
    public record SendRequest(@NotBlank @Size(max = 4000) String body, @NotNull UUID commandKey) {}
    public record LinkedRequest(@NotBlank String type, @NotNull UUID resourceId,
                                UUID parentId, @NotNull UUID commandKey) {}
    public record ReceiptRequest(@NotBlank String kind) {}
}
