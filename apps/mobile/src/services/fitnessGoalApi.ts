import { request } from '@/services/apiClient';
import {
  CreateFitnessGoalRequest,
  CreateGoalProposalRequest,
  CreateGoalTransitionRequest,
  CreateGoalVersionRequest,
  DecideGoalProposalRequest,
  FitnessGoal,
  FitnessGoalPageResponse,
  FitnessGoalVersion,
  GoalLifecycleStatus,
  GoalProposal,
  GoalProposalPageResponse,
  GoalTransition,
  GoalVersionPageResponse,
  PauseFitnessGoalRequest,
  ProposalDecision,
  ProposalStatus,
  ResumeFitnessGoalRequest,
} from '@/types/goal';

export const fitnessGoalApi = {
  /**
   * Retrieves current active fitness goal for the authenticated student.
   * If includePaused is true, returns the active goal or latest paused goal.
   */
  async getCurrentGoal(includePaused = true): Promise<FitnessGoal> {
    const query = includePaused ? '?includePaused=true' : '';
    return request<FitnessGoal>(`/fitness-goals/me/current${query}`, {
      method: 'GET',
      requiresAuth: true,
    });
  },

  /**
   * Lists fitness goals for the authenticated student with optional status filtering.
   */
  async getGoals(params?: {
    status?: GoalLifecycleStatus;
    page?: number;
    size?: number;
  }): Promise<FitnessGoalPageResponse> {
    const searchParams = new URLSearchParams();
    if (params?.status) searchParams.append('status', params.status);
    if (params?.page !== undefined) searchParams.append('page', String(params.page));
    if (params?.size !== undefined) searchParams.append('size', String(params.size));
    const query = searchParams.toString() ? `?${searchParams.toString()}` : '';

    return request<FitnessGoalPageResponse>(`/fitness-goals${query}`, {
      method: 'GET',
      requiresAuth: true,
    });
  },

  /**
   * Retrieves details of a fitness goal by ID.
   */
  async getGoal(goalId: string): Promise<FitnessGoal> {
    return request<FitnessGoal>(`/fitness-goals/${goalId}`, {
      method: 'GET',
      requiresAuth: true,
    });
  },

  /**
   * Lists goal versions for a specific goal with pagination.
   */
  async getGoalVersions(
    goalId: string,
    page = 0,
    size = 20
  ): Promise<GoalVersionPageResponse> {
    return request<GoalVersionPageResponse>(
      `/fitness-goals/${goalId}/versions?page=${page}&size=${size}`,
      {
        method: 'GET',
        requiresAuth: true,
      }
    );
  },

  /**
   * Retrieves details of a specific goal version.
   */
  async getGoalVersion(goalId: string, versionId: string): Promise<FitnessGoalVersion> {
    return request<FitnessGoalVersion>(
      `/fitness-goals/${goalId}/versions/${versionId}`,
      {
        method: 'GET',
        requiresAuth: true,
      }
    );
  },

  /**
   * Lists goal transitions for a specific goal.
   */
  async getGoalTransitions(goalId: string): Promise<GoalTransition[]> {
    return request<GoalTransition[]>(`/fitness-goals/${goalId}/transitions`, {
      method: 'GET',
      requiresAuth: true,
    });
  },

  /**
   * Retrieves details of a specific goal transition.
   */
  async getGoalTransition(goalId: string, transitionId: string): Promise<GoalTransition> {
    return request<GoalTransition>(
      `/fitness-goals/${goalId}/transitions/${transitionId}`,
      {
        method: 'GET',
        requiresAuth: true,
      }
    );
  },

  /**
   * Pauses an active fitness goal with a mandatory reason.
   */
  async pauseGoal(goalId: string, reason: string): Promise<FitnessGoal> {
    const payload: PauseFitnessGoalRequest = { reason };
    return request<FitnessGoal>(`/fitness-goals/${goalId}/pause`, {
      method: 'POST',
      requiresAuth: true,
      body: payload,
    });
  },

  /**
   * Resumes a paused fitness goal with a mandatory reason.
   */
  async resumeGoal(goalId: string, reason: string): Promise<FitnessGoal> {
    const payload: ResumeFitnessGoalRequest = { reason };
    return request<FitnessGoal>(`/fitness-goals/${goalId}/resume`, {
      method: 'POST',
      requiresAuth: true,
      body: payload,
    });
  },

  /**
   * Lists goal proposals for the authenticated student.
   */
  async getMyGoalProposals(params?: {
    status?: ProposalStatus;
    fitnessGoalId?: string;
    page?: number;
    size?: number;
  }): Promise<GoalProposalPageResponse> {
    const searchParams = new URLSearchParams();
    if (params?.status) searchParams.append('status', params.status);
    if (params?.fitnessGoalId) searchParams.append('fitnessGoalId', params.fitnessGoalId);
    if (params?.page !== undefined) searchParams.append('page', String(params.page));
    if (params?.size !== undefined) searchParams.append('size', String(params.size));
    const query = searchParams.toString() ? `?${searchParams.toString()}` : '';

    return request<GoalProposalPageResponse>(`/fitness-goal-proposals/me${query}`, {
      method: 'GET',
      requiresAuth: true,
    });
  },

  /**
   * Retrieves details of a specific goal proposal.
   */
  async getGoalProposal(proposalId: string): Promise<GoalProposal> {
    return request<GoalProposal>(`/fitness-goal-proposals/${proposalId}`, {
      method: 'GET',
      requiresAuth: true,
    });
  },

  /**
   * Student accepts or rejects a goal proposal with confirmation/note.
   */
  async decideGoalProposal(
    proposalId: string,
    decision: ProposalDecision,
    decisionNote?: string | null
  ): Promise<GoalProposal> {
    const payload: DecideGoalProposalRequest = {
      decision,
      decisionNote: decisionNote && decisionNote.trim() ? decisionNote.trim() : null,
    };
    return request<GoalProposal>(`/fitness-goal-proposals/${proposalId}/decisions`, {
      method: 'POST',
      requiresAuth: true,
      body: payload,
    });
  },

  /**
   * Creates a new fitness goal for the authenticated student.
   */
  async createGoal(data: CreateFitnessGoalRequest): Promise<FitnessGoal> {
    return request<FitnessGoal>('/fitness-goals', {
      method: 'POST',
      requiresAuth: true,
      body: data,
    });
  },

  /**
   * Activates a draft fitness goal.
   */
  async activateGoal(goalId: string): Promise<FitnessGoal> {
    return request<FitnessGoal>(`/fitness-goals/${goalId}/activate`, {
      method: 'POST',
      requiresAuth: true,
    });
  },

  /**
   * Creates a new goal version for an existing fitness goal.
   */
  async createGoalVersion(
    goalId: string,
    data: CreateGoalVersionRequest
  ): Promise<FitnessGoalVersion> {
    return request<FitnessGoalVersion>(`/fitness-goals/${goalId}/versions`, {
      method: 'POST',
      requiresAuth: true,
      body: data,
    });
  },

  /**
   * Transitions an existing fitness goal to a new journey.
   */
  async createGoalTransition(
    goalId: string,
    data: CreateGoalTransitionRequest
  ): Promise<GoalTransition> {
    return request<GoalTransition>(`/fitness-goals/${goalId}/transitions`, {
      method: 'POST',
      requiresAuth: true,
      body: data,
    });
  },

  /**
   * Trainer proposes changes to a student's fitness goal.
   */
  async createGoalProposal(
    goalId: string,
    data: CreateGoalProposalRequest
  ): Promise<GoalProposal> {
    return request<GoalProposal>(`/fitness-goals/${goalId}/proposals`, {
      method: 'POST',
      requiresAuth: true,
      body: data,
    });
  },

  /**
   * Authorized Trainer fetches student's current active/paused fitness goal.
   */
  async getStudentCurrentGoalForTrainer(studentId: string): Promise<FitnessGoal> {
    return request<FitnessGoal>(`/students/${studentId}/fitness-goals/current`, {
      method: 'GET',
      requiresAuth: true,
    });
  },

  /**
   * Authorized Trainer lists fitness goal proposals for a student.
   */
  async getStudentGoalProposalsForTrainer(
    studentId: string,
    params?: {
      fitnessGoalId?: string;
      status?: ProposalStatus;
      page?: number;
      size?: number;
    }
  ): Promise<GoalProposalPageResponse> {
    const searchParams = new URLSearchParams();
    if (params?.status) searchParams.append('status', params.status);
    if (params?.fitnessGoalId) searchParams.append('fitnessGoalId', params.fitnessGoalId);
    if (params?.page !== undefined) searchParams.append('page', String(params.page));
    if (params?.size !== undefined) searchParams.append('size', String(params.size));
    const query = searchParams.toString() ? `?${searchParams.toString()}` : '';

    return request<GoalProposalPageResponse>(`/students/${studentId}/fitness-goal-proposals${query}`, {
      method: 'GET',
      requiresAuth: true,
    });
  },
};
