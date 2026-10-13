package com.fitnesscoaching.platform.modules.content.adapter.out.event;

import com.fitnesscoaching.platform.modules.content.application.port.out.AiIndexingNotificationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Adapter notifying the AI subsystem asynchronously about knowledge publication events.
 * Keeps AI indexing boundaries decoupled from the primary content publishing transaction.
 */
@Component
public class LoggingAiIndexingNotificationAdapter implements AiIndexingNotificationPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingAiIndexingNotificationAdapter.class);

    @Override
    @Async
    public void notifyKnowledgePublished(UUID versionId, String documentCode, String versionLabel, String sourceId) {
        log.info("Knowledge version activated for AI indexing eligibility: versionId={}, documentCode={}, versionLabel={}, sourceId={}",
                versionId, documentCode, versionLabel, sourceId);
    }
}
