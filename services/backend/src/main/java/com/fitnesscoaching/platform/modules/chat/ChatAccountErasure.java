package com.fitnesscoaching.platform.modules.chat;

import java.util.List;
import java.util.UUID;

/** Chat-owned work; no Account component reads Chat persistence directly. */
public interface ChatAccountErasure {
    record ObjectReference(UUID mediaId, String bucket, String key, String storageProvider,
                           boolean ownerVerified) {}
    List<ObjectReference> concealAndListSentObjects(UUID userId);
    void redactSentMessages(UUID userId);
}
