package com.fitnesscoaching.platform.modules.schedule.application.port.out;

import com.fitnesscoaching.platform.modules.schedule.application.port.in.ScheduleChangeProposalUseCase.Proposal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScheduleChangeProposalStore {
    Instant databaseNow();
    Optional<Proposal> find(UUID proposalId);
    Optional<Proposal> lock(UUID proposalId);
    List<Proposal> listStudent(UUID studentId, String status, int size, long offset);
    long countStudent(UUID studentId, String status);
    List<Proposal> listTrainer(UUID trainerId, UUID studentId, String status, int size, long offset);
    long countTrainer(UUID trainerId, UUID studentId, String status);
    Optional<Receipt> lockReceipt(UUID actorId, String commandKey);
    void save(Proposal proposal, String commandKey);
    void transition(Proposal proposal, String oldStatus, UUID actorId, String commandKey,
                    UUID appliedOccurrenceId, Instant at);
    void saveReceipt(UUID actorId, String commandKey, String commandName, String payloadHash,
                     UUID proposalId, String responseJson, Instant at);

    record Receipt(String commandName, String payloadHash, String responseJson) {}
}
