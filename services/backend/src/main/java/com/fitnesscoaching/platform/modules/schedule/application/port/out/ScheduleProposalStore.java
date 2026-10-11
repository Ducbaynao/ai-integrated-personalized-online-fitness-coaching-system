package com.fitnesscoaching.platform.modules.schedule.application.port.out;

import com.fitnesscoaching.platform.modules.schedule.application.port.in.ScheduleProposalUseCase.Proposal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScheduleProposalStore {
    Instant databaseNow();
    Optional<Proposal> find(UUID proposalId);
    Optional<Proposal> lock(UUID proposalId);
    List<Proposal> listStudent(UUID studentId, String status, int limit, long offset);
    long countStudent(UUID studentId, String status);
    Optional<Receipt> lockReceipt(UUID actorId, String commandKey);
    void save(Proposal proposal, String commandKey);
    void transition(Proposal proposal, String fromStatus, UUID actorId, String commandKey, Instant at,
                    UUID confirmedBatchId);
    void saveReceipt(UUID actorId, String commandKey, String commandName, String payloadHash,
                     UUID proposalId, UUID batchId, String responseJson, Instant at);

    record Receipt(String commandName, String payloadHash, String responseJson) {}
}
