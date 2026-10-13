package com.fitnesscoaching.platform.modules.content.application.port.out;

import java.util.UUID;

/**
 * Outbound port for notifying the AI subsystem that a knowledge version has been published.
 * Must run asynchronously/decoupled from the Spring content publishing transaction.
 */
public interface AiIndexingNotificationPort {

    void notifyKnowledgePublished(UUID versionId, String documentCode, String versionLabel, String sourceId);
}
