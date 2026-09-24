package com.fitnesscoaching.platform.modules.goal.application.port.out;

import com.fitnesscoaching.platform.modules.goal.domain.FitnessGoalVersion;
import com.fitnesscoaching.platform.modules.goal.domain.GoalProposal;
import com.fitnesscoaching.platform.modules.goal.domain.ProposalStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GoalProposalPort {

    GoalProposal saveProposal(GoalProposal proposal);

    Optional<GoalProposal> findById(UUID proposalId);

    List<GoalProposal> findByStudentId(UUID studentId, ProposalStatus statusFilter, int limit, long offset);

    long countByStudentId(UUID studentId, ProposalStatus statusFilter);

    boolean rejectProposal(UUID proposalId, UUID studentId, String decisionNote);

    FitnessGoalVersion acceptProposal(GoalProposal proposal, UUID studentId, String decisionNote);
}
