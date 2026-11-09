export type MetricValueType = 'NUMERIC' | 'TEXT' | 'BOOLEAN';

export interface MetricDefinition {
  id: number;
  code: string;
  displayName: string;
  description?: string | null;
  valueType: MetricValueType;
  defaultUnitId?: number | null;
  defaultUnitCode?: string | null;
  defaultUnitSymbol?: string | null;
  defaultDimension?: string | null;
  validMin?: number | null;
  validMax?: number | null;
  displayPrecision?: number | null;
  isActive: boolean;
}

export type MeasurementValidationStatus = 'PENDING' | 'VALID' | 'SUSPECT' | 'REJECTED' | 'SUPERSEDED';

export interface Measurement {
  id: string;
  studentId: string;
  metricDefinitionId: number;
  metricCode: string;
  metricDisplayName: string;
  numericValue?: number | null;
  textValue?: string | null;
  booleanValue?: boolean | null;
  unitId?: number | null;
  unitCode?: string | null;
  unitSymbol?: string | null;
  measuredAt: string;
  sourceCode?: string | null;
  sourceName?: string | null;
  sourceKind?: string | null;
  methodCode?: string | null;
  methodName?: string | null;
  enteredBy: string;
  confidenceScore?: number | null;
  qualityScore?: number | null;
  validationStatus: MeasurementValidationStatus;
  supersededById?: string | null;
  notes?: string | null;
  createdAt: string;
}

export interface MeasurementPageResponse {
  content: Measurement[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasMore: boolean;
}

export interface RecordMeasurementRequest {
  studentId?: string | null;
  metricCode: string;
  numericValue?: number | null;
  textValue?: string | null;
  booleanValue?: boolean | null;
  unitCode?: string | null;
  measuredAt: string;
  methodCode?: string | null;
  notes?: string | null;
}

export interface CorrectMeasurementRequest {
  numericValue?: number | null;
  textValue?: string | null;
  booleanValue?: boolean | null;
  unitCode?: string | null;
  measuredAt?: string | null;
  notes?: string | null;
  reason: string;
}

export type PhotoViewType = 'FRONT' | 'BACK' | 'SIDE_LEFT' | 'SIDE_RIGHT' | 'OTHER';

export interface ProgressPhoto {
  id: string;
  studentId: string;
  mediaFileId: string;
  takenAt: string;
  viewType: PhotoViewType;
  weightAtTime?: number | null;
  weightUnitCode?: string | null;
  notes?: string | null;
  createdAt: string;
}

export interface ProgressPhotoPageResponse {
  content: ProgressPhoto[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasMore: boolean;
}

export interface UploadProgressPhotoParams {
  studentId?: string;
  fileUri: string;
  fileName: string;
  mimeType: string;
  viewType?: PhotoViewType;
  weightAtTime?: number;
  weightUnitCode?: string;
  takenAt?: string;
  notes?: string;
}

export type ProgressScope = 'CURRENT_GOAL' | 'LIFETIME';

export interface MetricDelta {
  metricCode: string;
  baselineValue?: number | null;
  latestValue?: number | null;
  deltaValue?: number | null;
  percentageChange?: number | null;
  trendDirection?: 'INCREASED' | 'DECREASED' | 'STABLE' | 'INSUFFICIENT_DATA';
  unitCode?: string | null;
}

export interface ProgressGoalSummary {
  id: string;
  title: string;
  status: string;
  startDate?: string | null;
  targetDate?: string | null;
  durationDays?: number | null;
  targets?: {
    metricCode: string;
    targetNumericValue?: number;
    unitCode?: string;
  }[];
}

export interface WorkoutAdherenceSummary {
  scheduledDueCount: number;
  completedDueCount: number;
  completedOnTimeCount: number;
  completedOffScheduleCount: number;
  missedCount: number;
  rawCompletionRate?: number | null;
  onTimeAdherenceRate?: number | null;
  availabilityReason?: string;
}

export interface TrainingVolumeSummary {
  knownVolumeKg?: number | null;
  totalSetsCompleted: number;
  workingSetsCompleted: number;
  totalRepsCompleted: number;
  averageSessionRpe?: number | null;
}

export interface ContinuitySummary {
  state: 'CONFIRMED_NEW' | 'PROLONGED_INACTIVITY' | 'RECENT_ACTIVITY_OBSERVED' | 'UNKNOWN_HISTORY' | 'RESTRICTED_HISTORY';
  daysSinceLastWorkout?: number | null;
  lastWorkoutDate?: string | null;
  rationale?: string;
  safetyFlags?: string[];
}

export interface ProgressDashboardSummary {
  studentId: string;
  scope: ProgressScope;
  windowStart: string;
  windowEnd: string;
  referenceTime: string;
  asOf: string;
  goal?: ProgressGoalSummary | null;
  workout: {
    availability: 'AVAILABLE' | 'NO_DATA' | 'RESTRICTED';
    adherence?: WorkoutAdherenceSummary;
    volumeAndIntensity?: TrainingVolumeSummary;
  };
  measurement: {
    availability: 'AVAILABLE' | 'NO_DATA' | 'RESTRICTED';
    summary?: {
      totalObservationsCount: number;
      distinctMetricsCount: number;
      distinctDaysCount: number;
    };
    metricDeltas?: MetricDelta[];
  };
  continuity: ContinuitySummary;
  historicalLimits: {
    hasHistoricalLimits: boolean;
    explanation?: string;
  };
  compositeWatermark: string;
}
