export type RecurrenceStatus = 'PENDING' | 'ACTIVE' | 'PAUSED_SOURCE' | 'STOPPED' | 'REJECTED' | 'WITHDRAWN';
export type RecurrenceOutcome = 'CREATED' | 'DST_GAP' | 'DST_AMBIGUOUS' | 'INVALID_INTERVAL' | 'SKIPPED_OVERLAP' | 'SKIPPED_UNKNOWN_END' | 'SKIPPED_DUPLICATE';

export interface RecurrenceItem {
  planSessionId: string;
  weekNumber: number;
  dayNumber: number;
  sequenceNumber: number;
  firstLocalStart: string;
  firstLocalEnd: string;
}

export interface RecurrenceCreate {
  planId: string;
  sourcePlanVersionId: string;
  expectedPlanAggregateVersion: number;
  mondayAnchorDate: string;
  timezone: string;
  items: RecurrenceItem[];
  commandKey: string;
}

export interface RecurrenceSeries extends Omit<RecurrenceCreate, 'expectedPlanAggregateVersion' | 'commandKey'> {
  seriesId: string;
  studentId: string;
  sourcePlanVersionNumber: number;
  observedPlanAggregateVersion: number;
  planWeeks: number;
  status: RecurrenceStatus;
  seriesVersion: number;
  initiatedBy: string;
  proposingTrainerId: string | null;
  coachingPeriodId: string | null;
  confirmedBy: string | null;
  createdAt: string;
  confirmedAt: string | null;
  stoppedAt: string | null;
  replayed: boolean;
}

export interface RecurrencePeriod {
  periodId: string;
  seriesId: string;
  blockIteration: number;
  planSessionId: string;
  localStart: string;
  localEnd: string;
  timezone: string;
  startUtcOffset: string | null;
  endUtcOffset: string | null;
  plannedStartAt: string | null;
  plannedEndAt: string | null;
  outcome: RecurrenceOutcome;
  occurrenceId: string | null;
  processedAt: string;
}

export interface RecurrencePage<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export interface RecurrenceDecision {
  expectedSeriesVersion: number;
  decision: 'ACCEPT' | 'REJECT';
  commandKey: string;
}

export interface RecurrenceVersionCommand {
  expectedSeriesVersion: number;
  commandKey: string;
}
