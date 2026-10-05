package com.fitnesscoaching.platform.modules.coaching.application.port.out;

import com.fitnesscoaching.platform.modules.coaching.domain.CoachingState.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CoachingStore {
    Instant transitionTime();
    void lockStudent(UUID studentId);
    Optional<Relationship> relationship(UUID id);
    Optional<Relationship> currentRelationship(UUID studentId);
    boolean pendingPair(UUID studentId, UUID trainerId);
    List<Relationship> pending(UUID actorId, boolean incoming, boolean studentCapability,
                               boolean trainerCapability, int limit, int offset);
    Relationship create(UUID studentId, UUID trainerId, UUID initiator, Instant at);
    Relationship transition(Relationship relationship, String next, UUID actor, String reason, Instant at);
    List<History> history(UUID relationshipId, int limit, int offset);
    Optional<Period> effectivePeriod(UUID studentId, Instant at);
    List<Period> periods(UUID studentId, int limit, int offset);
    void closePeriod(Period period, Instant at);
    Period openPeriod(UUID studentId, String mode, UUID relationshipId, UUID trainerId, UUID actor, Instant at);
    Optional<Resume> resume(UUID id);
    Optional<Resume> pendingResume(UUID relationshipId);
    List<Resume> resumeHistory(UUID relationshipId, int limit, int offset);
    Resume createResume(UUID relationshipId, UUID initiator, String reason, Instant at);
    Resume decideResume(Resume resume, String next, UUID actor, Instant at);
    Optional<Resume> cancelPendingResume(UUID relationshipId, UUID actor, Instant at);
    Optional<Receipt> receipt(UUID actor, UUID key);
    void saveReceipt(UUID actor, UUID key, String command, String hash, String json);
}
