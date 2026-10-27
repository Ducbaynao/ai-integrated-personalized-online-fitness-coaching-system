package com.fitnesscoaching.platform.modules.chat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Chat-owned view of the requesting actor's messages; current Chat authority still applies. */
public interface ChatOwnedExportQuery {
    record OwnMessage(UUID id, UUID conversationId, String body, Instant sentAt,
                      UUID attachmentId, String attachmentName, String attachmentContentType) {}

    List<OwnMessage> ownMessages(UUID actor);

    void requireOwnMessageReadable(UUID actor, UUID messageId);

    ChatService.AttachmentContent ownAttachment(UUID actor, UUID attachmentId);
}
