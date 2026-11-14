export type F04RunStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'REJECTED_BY_VALIDATOR';

export type F04ResultType = 'INFORMATION' | 'INSUFFICIENT_DATA' | 'BLOCKED';

export interface F04MetricEvaluation {
  metricCode: string;
  trendDirection: 'INCREASED' | 'DECREASED' | 'STABLE' | 'INSUFFICIENT_DATA' | string;
  message: string;
}

export interface F04MeasurementProgressSummary {
  metricsEvaluations?: F04MetricEvaluation[];
}

export interface F04WorkoutProgressSummary {
  completionEvaluation?: string;
  adherenceEvaluation?: string;
  volumeEvaluation?: string;
}

export interface F04ContinuityEvaluation {
  state: 'CONFIRMED_NEW' | 'PROLONGED_INACTIVITY' | 'RECENT_ACTIVITY_OBSERVED' | 'UNKNOWN_HISTORY' | 'RESTRICTED_HISTORY' | string;
  message: string;
  hasSafetyAlert: boolean;
}

export interface F04Usage {
  promptTokens?: number;
  completionTokens?: number;
  totalTokens?: number;
}

export interface F04ProgressSummaryApiRequest {
  studentId?: string;
  fromDate: string;
  toDate: string;
  timezone?: string;
  metricCodes?: string[];
  correlationId?: string;
}

export interface F04ProgressSummaryResponse {
  runId: string;
  correlationId?: string;
  studentId: string;
  actorId: string;
  fromDate?: string;
  toDate?: string;
  timezone?: string;
  requestedMetricCodes?: string[];
  metricScope?: 'ALL' | 'SELECTED' | 'UNKNOWN_LEGACY';
  runStatus: F04RunStatus;
  resultType: F04ResultType;
  reasonCode?: string;
  summary: string;
  workoutProgressSummary?: F04WorkoutProgressSummary | null;
  measurementProgressSummary?: F04MeasurementProgressSummary | null;
  continuityEvaluation?: F04ContinuityEvaluation | null;
  limitations?: string[];
  missingData?: string[];
  factsReferenced?: string[];
  asOf: string;
  sourceWatermark?: string;
  usage?: F04Usage;
  isStale?: boolean | null;
  needsRefresh?: boolean | null;
  currentWatermark?: string | null;
}
