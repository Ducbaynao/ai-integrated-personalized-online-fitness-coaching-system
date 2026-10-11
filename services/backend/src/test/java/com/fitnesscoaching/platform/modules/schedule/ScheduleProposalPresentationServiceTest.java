package com.fitnesscoaching.platform.modules.schedule;

import com.fitnesscoaching.platform.modules.schedule.application.port.in.ScheduleProposalUseCase.Proposal;
import com.fitnesscoaching.platform.modules.schedule.application.port.in.ScheduleProposalUseCase.ProposalItem;
import com.fitnesscoaching.platform.modules.schedule.application.service.ScheduleProposalPresentationService;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserDirectoryQuery;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleProposalSourceQuery;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleProposalSourceQuery.Source;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScheduleProposalPresentationServiceTest {
    @Test void missingPresentationsRemainNullWithoutChangingSourceIdentity() {
        UserDirectoryQuery users = mock(UserDirectoryQuery.class);
        WorkoutScheduleProposalSourceQuery sources = mock(WorkoutScheduleProposalSourceQuery.class);
        when(users.findDisplaySummaries(anyCollection())).thenReturn(Map.of());
        when(sources.findByVersionIds(anyCollection())).thenReturn(Map.of());
        UUID proposalId = UUID.randomUUID(), studentId = UUID.randomUUID(), trainerId = UUID.randomUUID();
        UUID planId = UUID.randomUUID(), versionId = UUID.randomUUID(), sessionId = UUID.randomUUID();
        ProposalItem item = new ProposalItem(UUID.randomUUID(), sessionId, 1, 1, 1,
                LocalDateTime.parse("2026-10-20T09:00:00"), LocalDateTime.parse("2026-10-20T10:00:00"),
                "+07:00", "+07:00");
        Proposal proposal = new Proposal(proposalId, 0, "PENDING", studentId, trainerId,
                UUID.randomUUID(), planId, versionId, 1, 0, LocalDate.parse("2026-10-19"),
                "Asia/Bangkok", List.of(item), Instant.parse("2026-10-11T00:00:00Z"),
                null, null, null, null, null);

        var view = new ScheduleProposalPresentationService(users, sources).present(proposal);
        assertThat(view.proposalId()).isEqualTo(proposalId);
        assertThat(view.trainerId()).isEqualTo(trainerId);
        assertThat(view.planId()).isEqualTo(planId);
        assertThat(view.sourcePlanVersionId()).isEqualTo(versionId);
        assertThat(view.items().getFirst().planSessionId()).isEqualTo(sessionId);
        assertThat(view.trainerDisplayName()).isNull();
        assertThat(view.sourcePlanName()).isNull();
        assertThat(view.items().getFirst().sourceSessionName()).isNull();
        assertThat(view.items().getFirst().sourceSessionFocus()).isNull();

        when(sources.findByVersionIds(anyCollection())).thenReturn(Map.of(versionId,
                new Source(UUID.randomUUID(), versionId, "Wrong plan", Map.of(sessionId,
                        new WorkoutScheduleProposalSourceQuery.Session(sessionId, "Wrong session", "Wrong focus")))));
        var mismatched = new ScheduleProposalPresentationService(users, sources).present(proposal);
        assertThat(mismatched.sourcePlanName()).isNull();
        assertThat(mismatched.items().getFirst().sourceSessionName()).isNull();
    }
}
