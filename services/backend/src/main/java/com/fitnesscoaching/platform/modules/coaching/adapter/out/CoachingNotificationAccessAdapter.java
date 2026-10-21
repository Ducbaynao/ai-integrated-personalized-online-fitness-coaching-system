package com.fitnesscoaching.platform.modules.coaching.adapter.out;

import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingNotificationAccessQuery;
import com.fitnesscoaching.platform.modules.coaching.application.port.out.CoachingStore;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class CoachingNotificationAccessAdapter implements CoachingNotificationAccessQuery {
    private final CoachingStore store;

    public CoachingNotificationAccessAdapter(CoachingStore store) { this.store = store; }

    @Override public boolean canRead(UUID actor, String entityType, UUID entityId) {
        if (actor == null || entityId == null) return false;
        UUID relationshipId;
        if ("COACHING_REQUEST".equals(entityType)) relationshipId = entityId;
        else if ("COACHING_RESUME_REQUEST".equals(entityType))
            relationshipId = store.resume(entityId).map(resume -> resume.relationshipId()).orElse(null);
        else return false;
        if (relationshipId == null) return false;
        return store.relationship(relationshipId)
                .filter(relationship -> relationship.participant(actor)
                        && (!actor.equals(relationship.trainerId())
                        || "PENDING".equals(relationship.status()) || "ACTIVE".equals(relationship.status())))
                .isPresent();
    }
}
