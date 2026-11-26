export interface GoalTypeOption {
  code: string;
  label: string;
}

export const GOAL_TYPE_OPTIONS: GoalTypeOption[] = [
  { code: 'FAT_LOSS', label: 'Giảm mỡ' },
  { code: 'MUSCLE_GAIN', label: 'Tăng cơ' },
  { code: 'STRENGTH', label: 'Tăng sức mạnh' },
  { code: 'IMPROVE_FITNESS', label: 'Cải thiện thể lực' },
  { code: 'MAINTAIN', label: 'Duy trì thể lực' },
];

const READONLY_GOAL_TYPE_LABELS: Record<string, string> = {
  FAT_LOSS: 'Giảm mỡ',
  MUSCLE_GAIN: 'Tăng cơ',
  STRENGTH: 'Tăng sức mạnh',
  IMPROVE_FITNESS: 'Cải thiện thể lực',
  MAINTAIN: 'Duy trì thể lực',
  WEIGHT_LOSS: 'Giảm cân',
  WEIGHT_GAIN: 'Tăng cân',
};

export function formatGoalTypeDisplay(code: string): string {
  const label = READONLY_GOAL_TYPE_LABELS[code];
  return label ? `${label} (${code})` : code;
}
