export type GoalLifecycleStatus =
  | 'DRAFT'
  | 'ACTIVE'
  | 'PAUSED'
  | 'COMPLETED'
  | 'ENDED'
  | 'ABANDONED'
  | 'REPLACED'
  | 'ARCHIVED';

export type ObjectivePriority = 'PRIMARY' | 'SECONDARY';

export interface GoalObjective {
  id: string;
  goalTypeId: number;
  goalTypeCode: string;
  goalTypeName: string;
  priority: ObjectivePriority;
  sortOrder: number;
  notes?: string | null;
}

export interface GoalTarget {
  id: string;
  metricDefinitionId: number;
  metricCode: string;
  metricDisplayName: string;
  exerciseVariationId?: string | null;
  startValue?: number | null;
  targetValue?: number | null;
  targetMinValue?: number | null;
  targetMaxValue?: number | null;
  unitId: number;
  unitCode: string;
  unitSymbol: string;
  targetRepetitions?: number | null;
  targetDate?: string | null;
  notes?: string | null;
  createdAt?: string;
}

export interface FitnessGoalVersion {
  id: string;
  versionNumber: number;
  title: string | null;
  startDate: string;
  targetDate: string | null;
  durationDays: number | null;
  effectiveFrom: string;
  effectiveUntil: string | null;
  resumeDate: string | null;
  changeReason: string | null;
  changeSummary: string | null;
  createdBy: string;
  sourceProposalId: string | null;
  createdAt: string;
  lockedAt: string | null;
  lockedBy: string | null;
  lockReason: string | null;
  isCurrent: boolean;
  objectives: GoalObjective[];
  targets: GoalTarget[];
}

export interface FitnessGoal {
  id: string;
  studentId: string;
  title: string;
  status: GoalLifecycleStatus;
  createdBy: string;
  activatedAt: string | null;
  pausedAt: string | null;
  completedAt: string | null;
  endedAt: string | null;
  statusReason: string | null;
  createdAt: string;
  updatedAt: string;
  currentVersion?: FitnessGoalVersion | null;
}

export interface FitnessGoalPageResponse {
  items: FitnessGoal[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface GoalVersionPageResponse {
  items: FitnessGoalVersion[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface GoalTransition {
  id: string;
  previousGoalId: string;
  newGoalId: string;
  transitionReason: string;
  proposalId?: string | null;
  initiatedBy: string;
  transitionedAt: string;
  notes?: string | null;
  newGoal?: FitnessGoal | null;
}

export type ProposalStatus = 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'CANCELLED' | 'EXPIRED';

export type ProposalSource = 'STUDENT' | 'TRAINER' | 'AI_ASSISTANCE' | 'SYSTEM';

export interface GoalProposalObjective {
  id: string;
  goalTypeId: number;
  goalTypeCode: string;
  goalTypeName: string;
  priority: ObjectivePriority;
  sortOrder: number;
  notes?: string | null;
}

export interface GoalProposalTarget {
  id: string;
  metricDefinitionId: number;
  metricCode: string;
  metricDisplayName: string;
  exerciseVariationId?: string | null;
  startValue?: number | null;
  targetValue?: number | null;
  targetMinValue?: number | null;
  targetMaxValue?: number | null;
  unitId: number;
  unitCode: string;
  unitSymbol: string;
  targetRepetitions?: number | null;
  targetDate?: string | null;
  notes?: string | null;
}

export interface GoalProposal {
  id: string;
  studentId: string;
  fitnessGoalId: string;
  baseGoalVersionId?: string | null;
  source: ProposalSource;
  createdBy: string;
  proposedTitle?: string | null;
  proposedStartDate?: string | null;
  proposedTargetDate?: string | null;
  proposedDurationDays?: number | null;
  reason: string;
  status: ProposalStatus;
  decidedBy?: string | null;
  decidedAt?: string | null;
  decisionNote?: string | null;
  expiresAt?: string | null;
  createdAt: string;
  updatedAt: string;
  objectives: GoalProposalObjective[];
  targets: GoalProposalTarget[];
  baseVersion?: FitnessGoalVersion | null;
}

export interface GoalProposalPageResponse {
  items: GoalProposal[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export interface PauseFitnessGoalRequest {
  reason: string;
}

export interface ResumeFitnessGoalRequest {
  reason: string;
}

export type ProposalDecision = 'ACCEPT' | 'REJECT';

export interface DecideGoalProposalRequest {
  decision: ProposalDecision;
  decisionNote?: string | null;
}
